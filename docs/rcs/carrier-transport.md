<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Carrier SIP/MSRP transport

The carrier transport reaches the carrier's own RCS service (GSMA Universal Profile over the
carrier's IMS core) from inside this app, without a provider app. It is one `RcsTransport` among the
others and is selected per subscription by `RouteSelector`; see
[architecture.md](architecture.md#route-selection).

Scope: 1:1 text, delivery and display receipts, and typing indicators. Groups, reactions, business
messaging and location are not implemented on this transport, and file transfer does not cross into
the main process.

## Two processes

The SIP and MSRP stack (JAIN-SIP, sockets, TLS, the framework `SipDelegate`) runs in a separate
process so a fault there cannot take down the UI.

| Process | Class | Role |
|---|---|---|
| main | `CarrierImsTransport` | Implements `RcsTransport`; registered in `ProviderRegistry`'s in-process slot; forwards calls to `:ims`; feeds `:ims` events into `RcsCallbackRouter` directly (no binder hop to the router). |
| `:ims` | `CarrierImsService` | Not exported; bound by explicit component. Owns the registration mode, the SR and DR engines, and a single worker thread on which all work runs. |

The SMS role and `PERFORM_IMS_SINGLE_REGISTRATION` are granted per uid, so `:ims` holds them as
well; only the fault domain is split. `BugleApplication.initializeSync` returns early in `:ims`, so
the RCS startup runs only in the main process.

The two halves talk over a `Messenger` pair (`CarrierImsSeam`): the facade sends control messages
and passes its own `Messenger` in `MSG_REGISTER_EVENTS`; the service replies with events. Payloads
are `Bundle`s carrying the contract parcelables. The service reads `KEY_SUB_ID` from each control
message and falls back to the subscription it was last started for; `CarrierImsTransport.sendImdn`
and `sendTyping` do not set the key, so receipts and typing always take that fallback.

| Control (main to `:ims`) | Event (`:ims` to main) |
|---|---|
| `MSG_START_FOR_SUB` (`RcsSubInfo`), `MSG_STOP_FOR_SUB` | `EVT_REG_STATE` (`REG_*`, reason) |
| `MSG_SEND_MESSAGE` (`RcsOutgoingMessage`) | `EVT_PROV_STATE` (`PROV_*`, caps) |
| `MSG_WARM_PEER` | `EVT_INCOMING_MESSAGE` (`RcsIncomingMessage`) |
| `MSG_SEND_IMDN`, `MSG_SEND_TYPING` | `EVT_MESSAGE_STATUS` (`STATUS_*`, reason) |
| `MSG_SUBMIT_OTP` (ignored: no OTP on this transport) | `EVT_TOS_STATE` (`TOS_*`, `RcsTosPrompt`) |
| `MSG_REJECT_FILE` | `EVT_SESSION_WARM`, `EVT_SESSION_COLD` |

A `Messenger` has no synchronous return and cannot carry a `ParcelFileDescriptor` cleanly. That is
why `sendMessage` answers locally (below), why `getCapabilitiesForSub` returns the static caps once
the conduit is up, why `lookupRcsCapability` always returns `CAP_UNKNOWN`, and why the file-transfer
codes (`MSG_SEND_FILE`, `MSG_ACCEPT_FILE`, `EVT_INCOMING_MEDIA`) are reserved but not wired.

If `:ims` dies the facade clears its state, including the warm-peer set, and rebinds; the UI process
is unaffected.

## Identity in the registry

| Property | Value |
|---|---|
| `getProviderCaps().priority` | 50. A bound provider that declares a higher priority wins a line both can serve; the carrier transport is then the fallback. |
| `featureFlags` | `FEATURE_TEXT`, `FEATURE_IMDN`, `FEATURE_TYPING` |
| `supportedTransports` | `TRANSPORT_CARRIER_MSRP` |
| `canServeSub` | Always `LINE_MAYBE`: eligibility depends on live modem and IMS state, which only `startForSub` can probe. |
| `isAttached` | The `:ims` conduit is bound. |

## Registration modes

`CarrierImsMode.probe(context, subId)` asks
`ImsManager.getSipDelegateManager(subId).isSupported()`, which covers both "the carrier declares
single registration" and "the modem's IMS service offers `SipDelegate`":

| Result | Mode |
|---|---|
| true | `SR`, single registration: ride the modem's IMS registration through a framework `SipDelegate`. |
| false, or `SecurityException` | `DR`, dual registration: this app performs its own SIP `REGISTER` over the IMS bearer. |
| `ImsException` or anything else | `UNKNOWN`: the IMS service is not up yet. Retryable, not proof of either mode. |

`CarrierImsService.driveStart` emits `PROV_IN_PROGRESS`, probes, and starts the chosen engine. On
`UNKNOWN` it re-probes after 2, 5, 10, 20 and 30 s (six attempts in total), emitting
`REG_REGISTERING` each round, then gives up with `REG_FAILED` so the line falls back to SMS and a
later selection trigger can try again. Each start or stop bumps a generation counter so a pending
retry from a superseded start is discarded. On a debuggable build, `debug.rcs.dr.force=true` forces
`DR`.

### SR: single registration

1. `ShannonRcsConfigTrigger.trigger()` declares this client to the modem
   (`ProvisioningManager.setRcsClientConfiguration`), registers an RCS provisioning callback and
   calls `triggerRcsReconfiguration()`, so the modem fetches the RCS configuration itself and
   returns it through the callback. These calls require `PERFORM_IMS_SINGLE_REGISTRATION`, which
   only the SMS role holder has; that is why this runs in this app.
2. `SipDelegateClient.create()` requests a delegate for the CPM feature tags (`CpmFeatureTags`).
   Tag values are URL-encoded exactly as the framework compares them against the carrier's allowed
   list.
3. When the `oma.cpm.session` tag is granted, emit `REG_REGISTERED` and `PROV_CONFIGURED`; when the
   delegate is destroyed, `REG_UNREGISTERED`.
4. A modem may bring up its IMS registration before the RCS configuration is provisioned and not
   register again on its own afterwards, which leaves the tag granted to the app but absent from the
   network registration. The service therefore calls `triggerFullNetworkRegistration` every 6 s, up
   to five times, until the tag is registered.

Chat messages ride sessions: `SipDelegateClient.sendOnSession` sends an INVITE with an SDP offer
through the delegate, opens MSRP over TLS to the relay named in the answer (`ImsMsrpSocketFactory`,
bound to an IMS-capable cellular network), and sends the CPIM body as an MSRP SEND
(`CpmSessionEngine`). The session is held per peer, keyed by `+E164` with a per-peer lock, so sends
to different peers run concurrently; it is reused for later sends and torn down after 30 s idle
(`SESSION_IDLE_MS`), on a peer BYE or on delegate loss. Inbound INVITEs on the granted tag are
answered by `CpmIncomingSessionEngine`, which connects actively to the relay in the offer and
surfaces each CPIM text as an incoming message. An inbound session that sees neither a BYE nor a
frame for 10 minutes (`INBOUND_SESSION_TTL_MS`) is closed.

`CpmSessionEngine` completes a send on the MSRP 200 (`MSRP_ACKED`) or on a `REPORT` carrying the
in-flight message id: a 2xx Status is `DELIVERED`, any other Status fails the send
(`MsrpReportStatus`). A peer BYE is answered with a 200, which the framework does not send.

Delivery and display receipts and typing indicators go out in pager mode, as one SIP MESSAGE each
(`CpmSipMessageBuilder`), not on a session.

**SIP requests on this path.** Both builders write the request as text for the framework's
`SipMessage`, which puts no separator between the start line and the headers, so every start line
ends in CRLF; without it the modem rejects the glued line.

| Header | Session INVITE (`CpmSessionSipBuilder`) | Pager MESSAGE (`CpmSipMessageBuilder`) |
|---|---|---|
| Request URI, To | By `persist.rcs.cpm.requri`: `sip-home` (default) `sip:+E164@<home domain>;user=phone`, `sip-ims` the same user at the SIM's IMS domain (`ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org`, from `gsm.sim.operator.numeric`, falling back to `ims.mnc260.mcc310.3gppnetwork.org` when that is unset), or `tel` | Always `sip:+E164@<home domain>;user=phone`: the modem's serializer does not carry a bare `tel:` request URI |
| Route | The registration's Service-Route, as an out-of-dialog request requires (3GPP TS 24.229 §5.1.2A) | None |
| Access network | `P-Access-Network-Info`, `P-Last-Access-Network-Info`, `Cellular-Network-Info` and `Security-Verify`, echoed from the `SipDelegateConfiguration` | None |
| Contact, Accept-Contact | The URL-encoded `oma.cpm.session` tag (`urn%3Aurn-7%3A...`), because the framework's outgoing-message check compares these tags verbatim with the `DelegateRequest` set | Accept-Contact carries the literal `oma.cpm.session` URN with `;require;explicit` |
| P-Preferred-Service | The literal `oma.cpm.session` ICSI (not matched as a tag) | The literal `oma.cpm.session` ICSI |
| Via transport | `UDP` for transport type 0, `TLS` for any other value (TCP included) | Same |

The CPIM `From` and `To` of a 1:1 message are `sip:anonymous@anonymous.invalid`; the real
identities ride `P-Preferred-Identity` and the request URI. A receipt built by `buildImdn` carries
`imdn.Disposition-Notification: negative-delivery, positive-delivery, display` in its own CPIM
headers, so it requests receipts for itself; the DR path's `CpimMessage.newImdnReport` requests
none.

### DR: dual registration

`CarrierStackDrModeDriver` adapts the app-owned stack (`CarrierRcsTransport`, `CarrierSipRegistrar`,
`CarrierMessageSender`/`Receiver`, `CarrierMsrpSessionManager`) to the service. It still uses the
modem as the configuration fetcher: `startDr` installs the configuration listener and triggers the
same pull, and each document received is handed to `setAcsConfig`.

`configFor` builds the `RcsImsConfig`, first match wins:

1. The latest autoconfiguration document (RCC.07/RCC.14 `wap-provisioningdoc`), parsed by
   `AcsImsConfigParser`: P-CSCF address, home domain, private identity (IMPI), public identities,
   and the Digest credentials from `APPAUTH`. Where the document omits the password (an AKA
   deployment keeps the key on the ISIM), a debuggable build may supply one in `debug.rcs.dr.pw`; a
   password supplied this way is always treated as cleartext, never as an HA1.
2. On a debuggable build only: a document read from the file named by `debug.rcs.dr.acsfile`, then
   a configuration assembled from `debug.rcs.dr.*` properties (requires `impi` and `pw`).
3. Otherwise null: the driver reports `PROV_NOT_PROVISIONED` and `REG_FAILED`, and the line falls
   back to SMS rather than wedging.

`AcsImsConfigParser` matches parameter names case-insensitively:

| Parameter | Handling |
|---|---|
| `APPAUTH` `Realm`, `UserName`, `UserPwd`, `AuthType` | Also accepted as the aliases `AAuthRealm`, `AAuthName`, `AAuthSecret` and `AAuthType`. An `AuthType` or `AAuthType` containing `HA1` marks the secret as a precomputed HA1. Other clients read only the four standard names and honour `AuthType` only as `Digest`, so a document carrying an HA1 is specific to this client. |
| `Home_network_domain_Name` | The Digest realm when `APPAUTH` gives none. |
| `ftHTTPCSURI`, `ftHTTPDLURI`, `ftHTTPCSUser`, `ftHTTPCSPwd`, `MaxSizeFileTr` | File transfer. |

A configuration is usable (`CarrierTransportBridge.isConfigUsable`) when it has a P-CSCF address, a
domain, a user name and a public identity. The SIP transport defaults to TCP: the P-CSCF delivers a
terminating request larger than its UDP MTU over the UE's registered flow, and a UE registered over
UDP has no TCP listener for it, so large incoming requests such as an MSRP INVITE could not arrive.

Registration state maps directly: `REGISTERED` emits `REG_REGISTERED` and `PROV_CONFIGURED`,
`FAILED` emits `REG_FAILED` and `PROV_NOT_PROVISIONED`.

## SIP registration (DR)

`CarrierSipRegistrar` is a JAIN-SIP state machine; every SIP call runs on its `sipThread`.

```
UNREGISTERED --start--> REGISTERING --2xx--> REGISTERED
                          |-- 401/407, attempts < 3 --> REGISTER with Digest credentials
                          |-- 401/407 after 3 attempts --> FAILED
                          |-- any other final response  --> FAILED
REGISTERED --stop--> de-REGISTER (Expires: 0) --> UNREGISTERED
any --IOException / timeout--> FAILED
```

A 401 is answered from its `WWW-Authenticate` challenge with an `Authorization` header, a 407 from
its `Proxy-Authenticate` challenge with a `Proxy-Authorization` header (RFC 3261 §22.3), since a
proxy does not read `Authorization`. The status alone picks the header (`SipDigestAuth`), and the
de-REGISTER reusing a cached 407 challenge answers in `Proxy-Authorization` too.

`FAILED` is sticky until `stop()` and `start()`. The registrar does not schedule a refresh; the
registration lifetime is the requested `Expires` (600000 s by default, overridable with
`debug.rcs.dr.expires` on a debuggable build).

**Network plane.** `CarrierSipPlane`, from `debug.rcs.sip_plane` on a debuggable build (a user
build always takes `IMS_PDN`), is chosen, never fallen into:

| Plane | Bearer | P-CSCF |
|---|---|---|
| `IMS_PDN` (`ims`, the default; also any unrecognised value) | Requests `TRANSPORT_CELLULAR` + `NET_CAPABILITY_IMS` (restricted, hence `CONNECTIVITY_USE_RESTRICTED_NETWORKS`), waits up to 10 s, binds the process to it and uses its IPv4 address. | The PCO-signalled address from `LinkProperties.getPcscfServers()` when present, else the configuration's. |
| `CARRIER_DEFAULT_BEARER` (`carrier`) | The default network; the IMS bearer is not requested. | The configuration's only. A PCO address left over from another bearer is never consulted. |

On `IMS_PDN`, failing to acquire the IMS bearer is a failed precondition: the registrar logs it,
discards any PCO address, and continues on the default bearer, where the P-CSCF is expected to
refuse.

**REGISTER.** Request-URI `sip:<domain>`; From and To the public identity; Via with `rport`; a
`Contact` built as a raw header string, because JAIN-SIP's parameter setter writes a valueless tag
as `name=` and leaves a URN value unquoted, both of which a SIP parser rejects; `Route` to the
P-CSCF with `lr`; `Supported: path`; `User-Agent: IM-client/OMA1.0 lineageos-messaging/1.0`. The
Contact carries `+g.gsma.rcs.msgrevoke` and one `+g.3gpp.icsi-ref` whose quoted value lists the
`oma.cpm.session`, `oma.cpm.msg` and `oma.cpm.largemsg` ICSIs separated by commas (RFC 3840).
Repeating the parameter instead would let a server keep only the last value.

The initial REGISTER carries an empty Digest `Authorization` (user name and realm, empty nonce and
response) so the S-CSCF can identify the private identity and answer 401 rather than 403;
`debug.rcs.dr.noauth` omits it on a debuggable build. The challenge is cached and reused to
authenticate the de-REGISTER, because an unauthenticated de-REGISTER is challenged and the binding
would survive.

## Digest authentication

`SipDigestAuth` implements RFC 2617 Digest with MD5:

```
HA1      = MD5(username ":" realm ":" password)
HA2      = MD5(method ":" digest-uri)
response = MD5(HA1 ":" nonce ":" nc ":" cnonce ":" qop ":" HA2)   when qop is present
         = MD5(HA1 ":" nonce ":" HA2)                             otherwise
```

With `qop=auth` the registrar sends a fresh 16-hex-digit `cnonce` and `nc=00000001`. The realm is
the challenge's, else the configured one; the user name is the configured Digest user name (the
IMPI), else the SIP user name; the algorithm is the challenge's, else MD5.

When the provisioned credential is an HA1 rather than a password (`RcsImsConfig.authDigestIsHa1`),
`responseWithHa1` uses it directly, so no cleartext secret is stored on the device. It must have
been computed with the same user name and realm the registrar sends.

The same code computes HTTP Digest for the file-transfer content server (`CarrierFtHttpUploader`);
HTTP and SIP Digest differ only in method and URI. AKA (RFC 3310) is not implemented.

## Messaging (DR)

`CarrierRcsTransport.sendMessage` picks the path by the UTF-8 body size
(`CarrierTransportBridge.shouldUseMsrpSession`, threshold `MSRP_SWITCHOVER_SIZE_BYTES` = 1300):

| Size | Path |
|---|---|
| below 1300 bytes | Pager mode: one RFC 3428 SIP `MESSAGE` with a CPIM body (`CarrierMessageSender`). |
| 1300 bytes or more | An MSRP chat session to the peer: INVITE on first use, body queued until the session is established, then MSRP SEND. |

**Pager MESSAGE** (`CarrierMessageSender`). Request-URI and To are the peer (`tel:` or `sip:`), To
without a tag (pager mode is dialogless); `P-Preferred-Identity`; `P-Preferred-Service` and
`Accept-Contact` for the pager-mode ICSI `oma.cpm.msg` (percent-escaped and quoted in
Accept-Contact; the constant holding it is named `P_PREFERRED_SERVICE_CPM_SESSION`);
`Conversation-ID` and `Contribution-ID`, both RFC 4122 UUIDs, because a CPM application server
answers 400 to anything else; `Content-Type: message/cpim`. The two ids are one pair per sender
instance, so every pager message from one registration carries the same pair.

**CPIM** (`CpimMessage`, RFC 3862 with the RFC 5438 extensions). Headers are written in insertion
order, with the `NS` declarations placed immediately before the first prefixed header, which gives
`From`, `To`, `NS: imdn <urn:ietf:params:imdn>`, `imdn.Message-ID` (the app's message id, which is
how receipts correlate), `DateTime` (RFC 3339, UTC), `imdn.Disposition-Notification`, the order
peers send. `NS` is repeatable and kept in its own list, so one message can declare several
namespaces. Then come the inner MIME headers and payload. The inner type is
`text/plain;charset=UTF-8` for chat, `message/imdn+xml` with `Content-Disposition: notification`
for a receipt, and `application/im-iscomposing+xml` for typing. A chat message requests
`positive-delivery, display`; a receipt requests nothing. The session path also wraps its body in
CPIM, because the MSRP SEND declares `message/cpim`.

**Receipts** (`ImdnNotification`, RFC 5438 XML). Inbound, `CarrierMessageReceiver` answers the
MESSAGE with 200 OK, unwraps CPIM and maps an IMDN to `DELIVERED`, `DISPLAYED` or `FAILED`
(`failed`, `error` and `forbidden` all map to failed; `processed` is ignored). It sends a delivered
receipt automatically when the original asked for `positive-delivery`, over the same MSRP session
when the message arrived on one. The display receipt is sent explicitly by `sendImdnDisplay` when
the app reports the message read.

**Typing** (`IsComposingNotification`, RFC 3994) goes out as a pager MESSAGE with state `active` or
`idle`.

**Inbound requests.** The registrar forwards every request that is not part of its own REGISTER
transaction to `CarrierRcsTransport`: MESSAGE to the receiver, INVITE to
`CarrierMsrpSessionManager.onIncomingInvite` (answered, then MSRP established and SENDs delivered),
BYE to the session manager. Other methods are ignored. Responses other than to REGISTER are
forwarded too, so the session manager sees the 200 OK to its INVITE.

## MSRP sessions

| Piece | Behaviour |
|---|---|
| `CarrierMsrpSessionManager` | Owns the SIP dialog: INVITE with SDP offer (`Session-Expires: 1800`, not refreshed), 200 OK with answer, ACK, BYE. One session per peer. The local MSRP port is the SIP port + 1 for sessions we originate, + 2 for sessions we answer. |
| `SdpOffer` / `SdpAnswer` | A single `m=message` line (`TCP/TLS/MSRP` with an `msrps:` path when a local fingerprint is supplied, else `TCP/MSRP` with `msrp:`) with `a=path`, `a=setup` (RFC 6135), `a=connection:new`, `a=msrp-cema`, optional `a=fingerprint` (RFC 4572), `a=accept-types` and `a=accept-wrapped-types` (RFC 4975 §8.6). The answer picks the complementary setup role and intersects the accepted types. The `o=` sess-id must be numeric (RFC 4566 §5.2), so a hex MSRP session id is folded into a stable number there and stays hex in `a=path`. `formatFingerprint` writes lower-case colon-separated hex, although the RFC 4572 §5 grammar specifies upper case; `fingerprintMatches` compares case-insensitively. |
| `MsrpChatSession` | State machine `IDLE -> INVITING -> ESTABLISHED -> CLOSING -> CLOSED`; a rejected INVITE goes straight to `CLOSED`. Not thread-safe; driven from the SIP thread. |
| `MsrpTlsConnection` | One socket and one read thread. When the SDP carries an `a=fingerprint`, authenticates the peer by comparing its certificate's hash with it, not by host name, because relay paths use private addresses and opaque session ids. With no expected fingerprint, validation is skipped. Writes are serialised by a lock. |
| `MsrpClientCredentials` | SR path: an ephemeral self-signed RSA-2048 certificate, presented as the TLS client certificate and advertised by its SHA-256 fingerprint in our SDP. `ImsMsrpSocketFactory` trusts any server certificate at the TLS layer because the fingerprint comparison is the real check. |
| `CarrierMsrpSessionSender` | Splits a body with `MsrpChunker` (2048-byte chunks; every chunk but the last ends `+`, the last `$`; each chunk is its own transaction with a `Byte-Range`), correlates the per-chunk 200 responses by transaction id, and treats a `REPORT` with `Status: 000 200` as `SENT`. A failed chunk or a dropped session fails the message and stops sending it. |
| `CarrierMsrpSessionReceiver` | Answers each SEND chunk with 200 unless `Failure-Report: no`, reassembles by `Message-ID`, hands the body on as bytes (`onIncomingBytes`), and sends a `REPORT` when `Success-Report: yes`. An aborted or malformed message is dropped. |
| `MsrpReassembler` | Chunks must arrive in order: a chunk whose range does not start at the next expected offset is refused and the message dropped. A range with a known end must match its body length (an end of `*` trusts the body), the declared total may not change mid-message and must equal the assembled length, and a `#` end-flag discards the message. |
| `MsrpTransactionId` | 16 random bytes as 32 lowercase hex characters. |

`Byte-Range` totals count the payload only, not the CRLF before the end-line that the RFC 4975 §4
example includes. `MsrpMessage.parse` accepts the RFC's example frame as written; the reassembler
compares ranges against the body and would refuse it.

The SR path always offers MSRP over TLS with a fingerprint. The DR path originates sessions without
a local fingerprint, so its offer is `TCP/MSRP` and the socket is plain TCP; `debug.rcs.dr.msrptls`
forces a TLS socket for testing on a debuggable build.

On the DR answering side, `CarrierMsrpSessionManager.onIncomingInvite` always answers `TCP/MSRP`
without a fingerprint, whatever the offer's protocol, and does not check the offer's fingerprint.
The setup role is the complement of the offer's (an `active` offer makes us passive, `passive` or
`actpass` makes us active). Only the active role connects: it dials the relay in the offer and sends
a bodiless SEND first to bind the session at the relay (RFC 4975 §4.2.2), since the answering side
has nothing to send yet. Neither DR role runs an accept loop, so a session in which we are passive
receives no media.

An MSRP 200 or REPORT means the peer's MSRP stack has the message; on the DR path it maps to `SENT`,
not `DELIVERED`. Delivery is asserted only by an IMDN.

## Send status

`CarrierImsTransport.sendMessage` cannot wait for `:ims`, so it answers locally:

| Case | Answer |
|---|---|
| `:ims` not bound | Not accepted, `REASON_NOT_REGISTERED`. |
| Pager-sized body, or a peer with a warm session | Accepted; forwarded; the terminal status follows as `onMessageStatus`. |
| Session-sized body and no warm session | Not accepted (the caller sends SMS), and a session to the peer is warmed so the next attempt is accepted immediately. MSRP session setup takes seconds, which a synchronous accept cannot wait for. |

Warmth is mirrored from `:ims` by `EVT_SESSION_WARM` and `EVT_SESSION_COLD`, keyed
`subId|toUri`.

Terminal statuses:

| Engine | Mapping |
|---|---|
| SR | `CpmSessionEngine.Stage.MSRP_ACKED` becomes `STATUS_SENT`, `DELIVERED` (a 2xx MSRP REPORT for the message) becomes `STATUS_DELIVERED`, anything else `STATUS_FAILED`. |
| DR | `Message.Status` `SENT`, `DELIVERED`, `DISPLAYED`, `FAILED` map to the same `STATUS_*`; `SENDING` and other interim states are not reported. A driver that rejects the send outright reports `STATUS_FAILED` at once. |

On the DR pager path the final SIP response to the MESSAGE is not mapped to a status; the message
reaches a terminal state through the peer's IMDN.

## Inbound messages

`CarrierImsService.emitIncoming` builds the `RcsIncomingMessage`: sender as `+E164` (with
`sip:anonymous@unknown` when it cannot be parsed, because `fromUri` must not be null), both receipt
flags set, no group id, and the E2EE scheme id the body arrived under, if any. Bodies arrive
already unframed with their real content type; the service must not parse them again, because an
unframed image has no frame either and would be relabelled as text.

The DR inbound chain carries bytes. `Transport.Listener` has two byte forms:

| Method | Carries | Default |
|---|---|---|
| `onIncomingBytes` | A still-wrapped `message/cpim` envelope, which may hold a binary inner type. | Decodes the bytes as UTF-8 and calls the String form. |
| `onIncomingContent` | The final content of one message with its real, unframed content type; the only form that can carry bytes that are not UTF-8 text. | The lossy String form, for text-only listeners. |

Every hop between `CarrierMessageReceiver` and `CarrierImsService` overrides `onIncomingContent`.
On a session we answered, the session listener overrides `onIncomingBytes` and unwraps the CPIM
from the bytes. On a session we originated, the listener is wrapped by
`CarrierTransportBridge.wrap`, which forwards each form as itself, so the envelope reaches the same
unwrap as the bytes the session delivered.

The self number used as `From` is resolved once per start: `SubscriptionManager.getPhoneNumber`,
then the IMS registration's `P-Associated-URI`, then `RcsSubInfo.msisdn`, then (debuggable builds
only) `debug.rcs.dr.user`.

## Terms of service

When a configuration document arrives, `CarrierImsService.onRcsConfig` looks for a carrier
terms gate: a `characteristic` of type `MSG` (or a terms element). It extracts title, message and
button labels from either the `<parm name value>` or the element form, emits `TOS_REQUIRED` with an
`RcsTosPrompt` of kind `KIND_CARRIER_TOS`, and emits `TOS_NONE` once the gate disappears. Repeated
identical states are suppressed. The UI path is the same as for a provider's prompt
(`RcsCarrierTosReceiver`, `RcsCarrierTosActivity`).

## File transfer

`CarrierFtHttpUploader` uploads a file to the provisioned content server (`ftHTTPCSURI`) as a
`multipart/form-data` POST with HTTP Digest and returns the `application/vnd.gsma.rcs-ft-http+xml`
descriptor; inbound descriptors are downloaded the same way. Only single-shot upload is implemented,
not the resumable form. The main process cannot send or receive files over this transport;
`rejectIncomingFile` is forwarded and does nothing, because inbound file INVITEs are not routed.

## Debug properties

Every `debug.rcs.*` property below is read only on `eng` and `userdebug` builds: adb can set a
`debug.*` property on a user build. `persist.rcs.cpm.requri` is read on every build.

| Property | Effect |
|---|---|
| `debug.rcs.dr.force` | Force DR even when SR is supported. |
| `debug.rcs.sip_plane` | `ims` (default) or `carrier`; see the plane table. Any other value, an earlier plane name included, is the default. |
| `debug.rcs.dr.imsapn` | `false` skips binding the IMS bearer on `IMS_PDN`. |
| `debug.rcs.dr.acsfile` | Path of a configuration document to use when none came from the modem. |
| `debug.rcs.dr.impi`, `pw`, `user`, `impu`, `domain`, `pcscf`, `ha1` | Assemble a configuration without a document. Only here does `ha1=true` mark `pw` as a precomputed HA1. |
| `debug.rcs.dr.transport` | `SIPoTCP` (default), `SIPoUDP` or `SIPoTLS`. |
| `debug.rcs.dr.expires` | REGISTER `Expires`. |
| `debug.rcs.dr.localport` | Pin the local SIP port so repeated installs reuse one Contact. |
| `debug.rcs.dr.noauth` | Send the initial REGISTER without an empty `Authorization`. |
| `debug.rcs.dr.msrptls` | Use a TLS socket for DR MSRP sessions. |
| `debug.rcs.dr.ftip` | Connect to this address for the file-transfer content server instead of resolving its host. |
| `persist.rcs.cpm.requri` | SR request URI form: `sip-home` (default), `sip-ims` or `tel`; see the SR header table. |
