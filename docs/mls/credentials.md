<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# MLS credentials and certificate lifetime

The MLS credential is an X.509 leaf certificate (RCC.16 Annex A). This page covers how the app
keeps its own identity current, the participant key the certificate is bound to, the 30-day
remaining-lifetime floor, the RCC.16 §9.5.3 credential Self-Update that carries a renewed
certificate into each group, how credential refusals are routed to the member whose certificate is
actually stale, leaf-key rotation, and the identity-verification hash.

Related: [overview.md](overview.md) (who owns the identity),
[health-and-recovery.md](health-and-recovery.md) (the `expired-credential` server reason),
[group-lifecycle.md](group-lifecycle.md) (era advance), [budgets.md](budgets.md) (era budget,
KeyPackage publish timing), [rust-core.md](rust-core.md) (RCC.16 validation in Rust).

## Copies and clocks of one member's certificate

The clock that matters is not always the one on the device. For any member there are three
artefacts, and on the same device at the same moment they routinely differ:

| Copy | Where it lives | How it is read |
|---|---|---|
| The certificate the device holds | The identity store, refreshed from the provider | `MlsSelfLeafStatus.clientNotBefore/clientNotAfter` (ours only) |
| The credential in the group's ratchet tree | Each group's leaf for that member | `MlsSelfLeafStatus.groupNotAfter` (ours), `OpenMlsSession.memberValidity` (everyone) |
| The certificate in the member's published KeyPackages | The member's KeyPackage pool, read only by a claim | The claimed package's leaf, `MlsSession.inspectKeyPackage` |

A group leaf carries the credential that was current when the leaf was last written, and the MLS
library reuses it on every later commit unless a new signing identity is supplied. A device that
renews its certificate therefore keeps presenting the old one to every group it is in, and each
group ages on the original certificate's clock. The server validates the roster's copy, so
re-minting alone repairs nothing; the RCC.16 §9.5.3 Self-Update below is what carries the new
certificate into the group.

A published pool is a third clock of its own. A pool can still serve a package minted under an
earlier certificate than the one its owner holds now, so the certificate on a claimed package can
be well short of the owner's current one.

### The KeyPackage lifetime is not the certificate's

A KeyPackage's LeafNode carries its own RFC 9420 `Lifetime`, and it is not the certificate's
validity. The engine anchors it at the certificate's `notBefore` (a peer checks it against its own
clock, and "now" fails for any peer whose clock trails) and the transport chooses the duration
(`OpenMlsEngine.KeyPackageLifetime`):

| Transport | Setting | `Lifetime.not_after` |
|---|---|---|
| provider | `FIXED_365_DAYS` | `notBefore` + 365 days: the provider's key directory requires the package to outlive the certificate |
| carrier | `WITHIN_CERTIFICATE` | the certificate window less 30 minutes, capped at 365 days (RCC.16 §5.1) |

On the provider transport a certificate lives about 75 days, so the lifetime still reads close to a
year when the certificate has days left, and a 30-day floor applied to the lifetime cannot fire
until the certificate is about 335 days old. Every floor in this page is therefore taken on the
certificate's X.509 `notAfter`, which is the instant the server quotes in its refusal.
`MlsSession.KeyPackageInfo` reports both clocks: `notAfterSecs` is the lifetime,
`certNotAfterSecs`/`certNotBeforeSecs` the certificate window, where `0/0` means "not readable",
never "expired" or "fine". It also reports the package's own cipher suite and the suites the leaf
advertises, separately, because mls-rs checks the first against the group and not the second
(RFC 9420 §7.2 requires the group's suite in every member's capabilities).

Log lines label which clock they print (`groupLeaf=` for the roster copy, `poolCert=` for a claimed
package) because the numbers look alike and mean different things. The operator dump
(`MlsTransportDiagnostics.dumpMemberValidity`) labels all three the same way.

## The certificate profile

`MlsCredential` builds the RCC.16 leaf (P-256, `ecdsa-with-SHA256`):

* EKU `id-kp-rcsMlsClient` (2.23.146.2.1.3), `KeyUsage = digitalSignature`, `SAN = tel:+E164`, a
  `subjectKeyIdentifier`, and exactly one non-critical `certificatePolicies` naming the E2EE policy
  2.23.146.2.1.2;
* a critical `.4` ParticipantInformation proof of possession, whose TBS is
  `SEQ{ leafSubject, INT 2, validity, participantSPKI, leafSAN }` signed ECDSA-SHA256 by the
  identity key, plus the `.5` key-roll and `.6` vendor-id extensions;
* a lifetime of at most 76 days, with at least 30 days remaining when used.

These are the same checks `Rcc16Validator` applies to a peer's certificate at add and join. The
signing key is durable (`MlsParticipantIdentityKey`); a renewal keeps the key and changes only the
certificate, which is why staleness is a certificate comparison, never a key comparison.
`MlsCredential` can also mint a local root, intermediate and leaf chain for the self-test, so the
engine round trip can run with no network.

## The participant key (carrier path)

On the carrier path the app enrols with the key server itself (`RcsKdsClient`), and the leaf is
bound to a durable P-256 **participant key** (RCC.16 §4.1, the A.3.8.9 extension).

**Why it must not change.** The configuration server retains this key and signs the
`SignedEncryptionIdentityProof` (RCC.16 §7.12.1) over it, and the key server requires the `.4`
participant key to equal the CSR's `identity_pub`. A key minted fresh per enrolment would make the
proof unsatisfiable and change identity on every process restart. The app emits no RCC.16 key rolls,
so a self-rotating key would orphan every proof and certificate bound to it. The proof itself is
carried to enrolment verbatim (`AcsImsConfigParser.ImsSettings.encryptionIdentityProof`): the client
cannot sign it and never rebuilds it.

**Derivation** (`MlsParticipantKeyDerivation`, in the shared engine library so that the app and the
provider reach the same key; the provider sends its SPKI to the configuration server, the app enrols
its point with the key server, and both must name one key):

1. `secret = SHA-256(DEVICE_SECRET_TAG ‖ ANDROID_ID)`.
2. `material = SHA-256(lenpref(LABEL_PARTICIPANT_KEY) ‖ lenpref(secret))`, with an MLS varint
   length prefix.
3. `s = (material mod (n − 1)) + 1`, `Q = s·G`, computed in plain Java (a platform key generator
   does not take a caller-chosen scalar) and loaded through `KeyFactory` on the named curve, so the
   encodings match a natively minted key for the same scalar. The provider has its own copy of the
   arithmetic, and a test checks the two produce byte-identical SPKI and PKCS#8.

`ANDROID_ID` is the same for two packages signed with the same key in the same user: the
per-package SSAID is an HMAC over the signing certificates, not the package name. A system-UID
caller would read the global `android_id` instead, which is why both apps must run as ordinary
apps. The key is a pure function of `(ANDROID_ID, label)`, so it rotates only on a factory reset;
changing `LABEL_PARTICIPANT_KEY` derives a different key on every device.
`spkiFingerprint` (the first 8 bytes of SHA-256 of the SPKI, in hex) gives both apps a comparable
`spki=` string for their logs.

**Storage** (`MlsParticipantIdentityKey`): preferences file `mls_lab_participant_key`
(`spki_der_b64`, `pkcs8_der_b64`; the file name is on-device state and stays as it is), written with
`commit()` so the key is on disk before it is handed out. A persisted key always wins over
re-derivation. `debug.rcs.acs_participant_key_mode` set to `random`, followed by `clear()`, mints a
random key: a new identity that diverges from the provider's, not a roll. Deriving a private key
from a device identifier suits a test trust domain and is weaker than a random hardware-backed key.

**Rolls.** The periodic identity refresh re-mints only the certificate; the persisted participant
key is reused, and the key server pins the registration-to-key binding. A real participant-key roll
comes with a new provisioning identity (factory reset or full re-provision), so the RCC.16 §10.1.1
resync trigger is re-provisioning, not ageing. `MlsParticipantKeyResync` (plans which of a
participant's clients one resync External Commit removes: one Remove plus `ServerRemove` proposals
for the rest) and `MlsParticipantKeyLedger` (decides which of a participant's keys is current by
folding successive roster readings, answering "" wherever the answer would be a guess) are
implemented and host-tested but have no production caller.

## Trust anchors (carrier path)

`MlsTrustAnchors.roots(Context)` asks the provider (`getMlsTrustAnchors`, on the default SMS
subscription) for a generation-addressed, signature-verified anchor list, and memoises it for the
process after the first success (`invalidate()` drops it, for example after a re-provision). The
configuration document's `openrcs-trust-anchors-uri`, `-generation` and `-signer` pointers reach
only the app, which passes them through. The supplied list is never merged with anything else, so a
withdrawn anchor stays withdrawn, and never merged with the provider transport's anchor set, which
is a different PKI.

When the provider supplies nothing (no bound provider, an empty or undecodable list, an exception),
the answer depends on the build, decided in `MlsCarrierTrust`:

| Build | Provider anchors | Anchors trusted | KDS | Carrier-path MLS |
|---|---|---|---|---|
| any | present | the provider's | as below, by build | on, with a KDS |
| `eng`, `userdebug` | none | the compiled-in root | configured, else the test network's | on |
| `user` | none | none | configured, else none | **off** |

The compiled-in root, its intermediate and the default KDS belong to a test network (the
intermediate's AIA and CRL name that network's KDS host). A user build never trusts or dials them:
every read in `MlsTrustAnchors` sits behind `RcsDebug.isDebugBuild()`, and
`TestNetworkTrustGuardTest` fails when one does not, or when any of those values appears in another
source file.

**Off means off, cleanly.** `CarrierRcsTransport.ensureMls` creates no MLS layer, so nothing is
enrolled or published and the receiver drops inbound MLS bodies; `RcsKdsClient.enroll` refuses to
enrol without an anchor, and fails rather than borrow the compiled-in intermediate when the KDS
returns no chain. In the main process `CarrierImsTransport.isMlsReady` answers false, so
`E2eeSendGate` never picks MLS for the conversation and its sends take the ordinary non-MLS route. A
send already tagged MLS that reaches `CarrierRcsTransport.sendMls` fails; it is never sent in the
clear.

**The user-build arm is inert today.** The carrier path resolves its anchors in the `:ims`
process, and nothing there binds the provider (`BugleApplication` skips the main-process
initialisation in `:ims`), so on a user build carrier-path MLS is off in practice. The main process
cannot see what `:ims` resolves, so `MlsCarrierTrust.readyInApp` claims MLS only on a debug build.
Carrying the provider's anchors into `:ims` must widen that answer in the same change.

## Keeping the identity current

`MlsIdentityRefresh` keeps the app's identity in step with the provider's certificate.

**Periodic re-read** (`maybeRefreshIdentity`), called on session bring-up. Two throttles, in two
preference keys with different meanings:

| Pref | Records | Throttle |
|---|---|---|
| `last_identity_refresh_ms` | Successful reads only | `MlsConfig.identityRefreshMs`: 7 days (`debug.rcs.mls_identity_refresh_days`) |
| `last_identity_attempt_ms` | Failed reads only | `MlsConfig.identityRetryBackoffMs`: 30 s |

The success stamp must record successes only: `onIdentityChanged` clears it to force an immediate
re-read after a failure, and stamping on attempt would strand the identity for a week. The failure
stamp exists because a throttle that records only successes cannot rate-limit a call that keeps
failing. A success clears the failure stamp.

**On a provider notification** (`onIdentityChanged`), which the provider sends only when the
issued chain actually changed (the comparison lives at the mint, not here):

1. Re-read the identity from the provider. On failure, do not publish KeyPackages under the old
   leaf; clear both stamps so the next session bring-up retries at once, and arm the group sweep
   anyway (the certificate did change).
2. Drop the engine session before generating KeyPackages. Every package embeds the leaf held at
   generation time, so generating from the cached session would publish a pool advertising a
   credential we no longer hold.
3. Clear the KeyPackage publish stamp and reopen the session, which republishes the pool.
4. Rewind the group sweep cursor and arm the sweep, so every group, including ones this walk has
   already passed, is re-examined for a Self-Update.

### Our KeyPackage pool

The published pool (`MlsKeyPackagePool`) holds `MlsConfig.kpPoolCount` packages
(`debug.rcs.mls_kp_count`, default 11, clamped to at least 2), one of which is the reusable
last-resort package, so `count − 1` are claimable. The remaining-count estimate is an **upper
bound**: it decrements only when we open a Welcome, while claims that never produce a Welcome
(abandoned establishes, rebuild re-claims, unopened Welcomes) consume packages uncounted. The key
server reports no published count, and probing by claiming would consume what it measures. So every
threshold leans toward republishing, which is cheap and idempotent; the timing is in
[budgets.md](budgets.md). A Welcome sealed to our last-resort package means the one-time pool is
empty, whatever the estimate says: the estimate is set to zero and then the ordinary republish check
(`maybePublishKeyPackages`) runs, which the zero makes republish at once instead of waiting for the
publish timer. A Welcome we cannot open triggers its own pool
repair ([group-lifecycle.md](group-lifecycle.md#joinfromwelcome)).

## The 30-day floor

RCC.16 requires every certificate in a group to have **at least 30 days remaining**, not merely to
be unexpired: the RCS service validates the whole post-commit roster at `now + 30 d` on every commit
and proposal (A.4.3.1 §1(a)), the client tier applies the same rule at claim time (A.4.1.x), and the
key server must not return a KeyPackage inside the window (A.4.2.2). One member inside the window
therefore refuses every commit on that group. The server has also been seen refusing a 1:1
application message while a member is inside the window (`expired-credential`, reason 104), so
until the stale member's Self-Update lands the other side cannot send at all. A refusal of this
kind states the instant it validated at; `MlsTimeValidationRefusal.validationSkewSecs` subtracts
device time from it, and
`skewMatchesFloor` checks that the difference equals the floor (2,592,000 s) within 60 s.

`MlsCredentialFloor` classifies a roster against the floor (`RCC16_MIN_REMAINING_DAYS` = 30). Pure
arithmetic; the caller supplies the clock. Remaining days are floored toward the past.

| `Standing` | Meaning | Counted in |
|---|---|---|
| `OK` | Above the floor. | |
| `INSIDE_FLOOR` | Unexpired but under 30 days left: the server will refuse commits. | `insideFloor` |
| `EXPIRED` | `notAfter <= now`. | `expired` |
| `NOT_YET_VALID` | `notBefore > now`: a clock or issuance problem, reported under its own standing because the remedy differs. | `expired` |
| `UNREADABLE` | The leaf would not parse (window `0/0`). Never folded into another standing. | `unreadable` |

`Report.below` names each member below the floor by MSISDN (or leaf index when the name is unknown)
with its `groupLeaf=` days. `Report.membershipChangesWouldBeRefused()` is true when any member is
inside the floor, expired or not yet valid. An unreadable member does not make it true: "we could
not read it" is not evidence against a member. `expiredMemberCount` returns -1, not 0, when validity
cannot be read, because "no expired members" and "we could not look" must not be the same answer
to a gate that issues era advances.

The floor is deliberately **not** an input to the maintenance pass's era-advance decision. An era
advance re-creates the group and must claim every member's KeyPackage, which the key server may not
return for a member inside the window; feeding the floor into that decision would turn a wedged
group into a loop of futile era advances. The floor is reported, and the one member we can repair is
ourselves.

### Where the floor is applied locally

| Where | Setting | Behaviour |
|---|---|---|
| Add and remove commits (`MlsServerBundle.membershipChangeAllowedByFloor`) | `debug.rcs.mls_floor_precheck`, default on | Refuses an add or remove locally when the roster's report says the server would refuse it, naming the members. An unreadable roster proceeds. Self-Update and leave are never gated: A.4.3.2 lets a member inside the floor send its own Self-Update, and a Remove gets no carve-out, so only the stale member's own Self-Update clears the group. The precheck cannot see a member not yet added. |
| A claimed peer KeyPackage (`MlsKeyPackagePolicy.keyPackageUsable`) | `MlsConfig.kpMinRemainingDays` (`debug.rcs.mls_kp_min_remaining_days`, default 30) | Refuses a package whose LeafNode lifetime has less than that left (A.4.1.2). Under the provider profile this check cannot fire (see above). |
| The same claimed package's certificate | `MlsConfig.kpCertFloor` (`debug.rcs.mls_kp_cert_floor`, default off) | Always evaluated and logged with both clocks; refuses only when the flag is on. |

The certificate check at claim time is off because the server's refusal is known on commits over an
existing roster, not yet on the member being added; if an add naming such a member is refused, the
flag is the switch. The check is reachable, since a pool can serve a package minted under an older
certificate. A package with no readable certificate window is logged as "not evaluated" rather than
passed as fine.

`keyPackageUsable` applies the rest of the consume-side checks in this order: the package must
parse; its SAN `tel:` MSISDN must equal the number that was queried (A.4.1, enforced by default;
`debug.rcs.mls_san_identity_check=0` turns it off on a debug build only; a non-X.509 credential has
no SAN and is left to chain validation); its own cipher suite must be the group's; its advertised
suites must include the group's (an unreadable list proceeds); then the two lifetime checks above.
A last-resort package is logged and accepted: refusing it would make a peer with an empty pool
unreachable, and a re-claim returns the same package.

## RCC.16 §9.5.3 credential Self-Update

RCC.16 §9.5.3: a client must update its certificate in each active group at least every 30 days by
sending an **empty Commit with an UpdatePath carrying the new leaf**. This is a rekey, not an era
advance: it changes one leaf and nothing else, and it is the only operation with an expiry
carve-out (A.4.3.2 §3: the committer's own existing leaf is not checked). An era advance would
re-create the group, force every member to re-join, and on a roster with stale members could not
even be built.

### Triggers

`MlsCredentialUpdate.maybeUpdateGroupCredential(cfg, shell, log, key, g, rcsGroupId, peerE164, cause)`
is called:

* first in every maintenance pass (`MlsMaintenancePass.runMaintenanceOnce`), before the server
  fetch. The question is answered entirely inside the engine, so a refused fetch cannot postpone it.
  The pass runs from the group sweep (armed on session bring-up and on identity change) and when a
  conversation is opened, and it has already declined a group we left;
* when the server refuses a control message naming our own credential
  (`reportCredentialRefusal`, below);
* when the server reports `expired-credential` and the group's copy of ours is stale
  (`expiredCredentialRemedy`, below).

### Refusals, in order

Each declines for its own reason and logs which one.

1. **Downgraded conversation** (`MlsRecordState.isDowngradedStatus`): a group that was taken out of
   MLS is not committed into (logged as `INVARIANT ED-1`, see
   [downgrade.md](downgrade.md#the-model)). The check is repeated here, not left to the maintenance
   pass, because the two refusal-routing paths below reach this method without it.
2. **Un-evaluable**: `selfLeafStatus` returned `null`. That is not "our credential is current".
3. **Not stale**: the ordinary case, silent.
4. **Our new certificate is inside the floor** (or its window would not parse). A.4.1.5 §5 forbids
   the Self-Update, and it would be refused for the same reason; the remedy is a fresh certificate.
5. **A peer is inside the floor, expired or not yet valid.** A.4.3.2 §3 exempts only the
   committer's own leaf, so the server still refuses a Self-Update whose roster holds a stale peer.
   Our own leaf is excluded from this check, since it is the one being replaced. Once a second
   member is inside the floor, nobody can commit. The attempt marker is not taken: the roster can
   clear without a new certificate.
6. **Already attempted** for this certificate at this position (below).

### One attempt per certificate, scoped by what the server judged

`ConvState.credentialUpdateAttemptedFor` (the `notAfter` of the certificate offered) and
`ConvState.credentialUpdateRefusedAt` (the group `Moment` the refusal applies to) bound re-offers.
They are in-memory, so a process restart also re-arms the update. The rule
(`MlsCredentialUpdateSeal`): **a refusal of the certificate seals the update until a new certificate
exists; a refusal of our position seals it only until we move.** Nothing here is a timer.

The attempt is taken before the commit, under the conversation lock, position-scoped, with the
previous values saved. The parked control verdict (`lastControlVerdict`, `lastControlDetail`) is
cleared under the same lock: `commitAndSend` has early returns (no session, no group) above its
own clear, and without this a previous operation's `VERDICT_REJECTED` would be read as this
attempt's and take the widest seal on a commit that never left the device. After the rekey:

| Outcome | Seal |
|---|---|
| The server was never asked (verdict still the `-1` sentinel: no commit artefact, or a test fixture withheld or refused it) | Released: restored to its previous value. |
| Connectivity loss (`VERDICT_NOT_REGISTERED`, `VERDICT_TRANSPORT_FAILED`) | Released. |
| The server refused our position (`MlsTransportDisposition.refusedOurPosition`: not registered, or not in the group) | Released. |
| Refused, but not `PERMANENT` (`isAboutTheBytes` false), e.g. `VERDICT_ERA_GAP` or `VERDICT_GROUP_ID_CHANGED` | Kept, **position-scoped**: catching up, an era advance or processing a peer's commit re-arms it. |
| Refused on a credential-validity error naming someone else, or nobody we can read (`judgedOurOwnCredential` false) | Kept, position-scoped. |
| Refused on the merits (`PERMANENT` and about our credential) | Widened: only a new certificate re-arms it. |
| Accepted, but `selfLeafStatus` still reports stale | Widened: the operation ran and did not achieve its purpose, and it moved the position, so a position-scoped seal would re-offer once per epoch. |
| Accepted and not stale | Success; returns `true`. |
| The commit path threw | Released: a throw carries no verdict about the bytes. |

The "never asked" release is tested on every outcome, not only on a failed era. `commitAndSend`
returns an era of zero or more from three places: an accepted commit, a silent outcome the server
turned out to hold (which writes `VERDICT_OK`), and the test fixture that withholds an applied
commit. Only the last leaves the sentinel, and the re-offers it allows are bounded by the fixture's
capacity, where it refuses and rolls back.

`VERDICT_REJECTED` is the only verdict that says the server evaluated the commit. An
expired-credential refusal reaches the app as `VERDICT_REJECTED` (`PERMANENT`), and it can name a
peer's MSISDN, so whose credential was judged is decided here
(`MlsCredentialUpdateSeal.judgedOurOwnCredential`) rather than by re-classifying the verdict, which
also drives recovery dispatch. The position-scoped seal matters most for the ordinary commit race on
a healthy group: a peer's commit lands first, ours is refused for the epoch, we process theirs, and
the update is then both possible and still needed.

`stillStands(sealedAt, positionNow)` treats a `null` position as "still sealed": re-offering a
commit on a guess that we moved costs an epoch and a self-heal per pass, while a suppressed repair
costs one maintenance cycle. The position is read before the commit, because a refused commit rolls
the group back and a later read could not tell "not moved" from "moved and came back".

Success is judged on the group, not on the certificate store: the post-condition is
`selfLeafStatus` reporting `stale == false`.

## Routing credential refusals to the right member

A credential-validity refusal (`MlsTimeValidationRefusal.parse` over the refusal text) names one
member's credential, and the remedy depends on whose it is. Re-minting our own identity when a
peer's certificate is the stale one replaces a healthy credential, rotates the published pool and
leaves the refusal where it was. Numbers are compared on their last ten digits, tolerant of a
leading `+` or country code.

**On the control-message plane** (`MlsCredentialUpdate.reportCredentialRefusal`, run from
`commitAndSend` after every refused commit), which carries an MSISDN:

| Named | Action |
|---|---|
| Us | RCC.16 §9.5.3 Self-Update (`maybeUpdateGroupCredential`). The server refused the group's copy of our leaf, which a re-mint does not touch. |
| A peer | Report the roster's floor standing. Nothing we send can fix it: a Remove gets no carve-out and the key server will not return their KeyPackage for a re-add; only their own Self-Update clears the group. Our identity is not re-minted. |
| Nobody readable | Report the roster so candidates are named. |

It also logs whether the server's validation instant equals device time plus the floor. It never
throws.

**On the negative-receipt plane** (`MlsCredentialUpdate.expiredCredentialRemedy`, for the server's
`expired-credential` reason), which carries no MSISDN, so the subject is determined locally:

1. The group's copy of our leaf is stale: RCC.16 §9.5.3 Self-Update.
2. Our own certificate is inside the floor: a new identity is needed
   (`MlsIdentityRefresh.onIdentityChanged`).
3. Neither: it is a peer's; report the roster and stop.

If the group cannot be read at all, it falls back to `onIdentityChanged`.

## Groups no commit can reach: the floor rebuild

Once a peer's in-group leaf is inside the floor, no client-side commit clears the group, the
Self-Update included. An era advance can, because it re-creates the group around freshly claimed
KeyPackages whose leaves come from the members' *published* pools, not from the old tree. That only
helps if every claimed package carries a certificate above the floor, which is true only once that
member has renewed and republished. `MlsFloorRebuild` gates it in two stages, both judged on
certificate `notAfter`, never on the LeafNode lifetime:

1. `assess` (from the roster, no network) returns a `Candidacy`:

   | `Candidacy` | When |
   |---|---|
   | `UNREADABLE` | The roster could not be read. |
   | `NOT_WEDGED` | Nothing is inside the floor, expired or not yet valid. |
   | `SELF_UPDATE_SUFFICES` | Only our own leaf is below: the Self-Update is the far cheaper repair. |
   | `OUR_CERTIFICATE_TOO_OLD` | A peer is below and so is the certificate we would build with, or ours is unknown (`<= 0`): rebuilding would install our own stale leaf and re-wedge the group. |
   | `CANDIDATE` | A peer is below and our certificate is above. |

   Our leaf is recognised by its index; when the engine cannot say (`-1`), every member below the
   floor counts as a peer, erring toward `CANDIDATE`, because the pre-flight still gates.
2. `preflight` (from the packages the era advance will actually consume) returns `READY`,
   `MEMBER_NOT_REPUBLISHED`, `UNDATED_PACKAGE` or `NOTHING_CLAIMED`, carrying every member in each
   class (`poolCert=` in the member lines). A package that cannot be dated is its own refusal, not a
   stale member. The rebuild is all or nothing: the new era must match the server's roster, so it
   cannot rebuild half a group.

The refusal does not depend on whether the server enforces the floor on a create: a rebuild that
installs a certificate already inside the floor re-creates the wedge.

`floorRebuild` then:

* declines downgraded and left groups;
* declines unless the two era-advance settings make a silent non-take safe
  (`MlsFloorRebuild.leverRefusal`): it proceeds only when `debug.rcs.mls_advance_create_fallback` is
  0 (so a create the server accepts without applying rolls back instead of dropping local state) and
  `debug.rcs.mls_advance_fresh_ctxid` is anything but `off` (every group a floor rebuild repairs has
  a stored context id, and a create that reuses it does not take). The refusal text asks for `all`;
  the check accepts any value other than `off`, `1to1` included, although `1to1` never mints for a
  group. Both settings are checked before the attempt marker is taken, so a missing setting costs
  nothing. The automatic era-advance callers do not apply this refusal (with the default settings it
  would disable every one of them); they only log the settings when an advance does not take
  ([group-lifecycle.md](group-lifecycle.md#era-advance));
* allows one attempt per certificate generation of ours (`ConvState.floorRebuildAttemptedFor`; the
  debug lever ignores it);
* runs the era advance with `requireRebuildableRoster`, so `MlsFloorRebuild.claimRosterForAdvance`
  refuses with `ERA_ADVANCE_ROSTER_NOT_READY` (-3) when the pre-flight does not go. Every other
  caller of that claim measures the same floor but only logs and proceeds, since a group that
  carries messages but refuses commits beats no recovery at all;
* is charged against the era budget like every other re-Welcome.

The maintenance pass runs it only when `debug.rcs.mls_floor_rebuild` is on (default off), because it
re-Welcomes every member, third parties included; with it off, the pass logs that a rebuild is the
only repair and names the debug lever that attempts one.

## Rotating our leaf key

Separately from the credential, our leaf's encryption key rotates with use (RCC.16's
`encryption_key_usage_level`), so the application ratchet returns to generation 0 before a long run
passes a receiver's `max_skip` window. `MlsRekeyPolicy`:

| Constant | Value | Meaning |
|---|---|---|
| `REKEY_AFTER_SENDS` | 256 | Rotate after this many application sends. The app's own choice, a constant rather than a setting. |
| `rotateAt(weGoFirst)` | 256 or 384 | Staggered: under mutual traffic both members reach the threshold together and their commits refuse each other, so the member that loses the tie-break (`MlsRecoveryPolicy.weAreEraAdvancer`, the lexicographically lower E.164 goes first) waits half as long again. |
| `REKEY_REFUSED_BACKOFF_SENDS` | 64 | A refused rotation drops both counters to 192 rather than restoring them, so the loser of a race lets the winner's commit land first. |

The rotation piggybacks on a send (`MlsAppMessage.encryptDispatching` asks the engine for a key
update with the message), so it cannot be lost: the engine applies the self-update commit locally,
returns a pre-commit snapshot with it, and the commit is sent after the conversation lock is
released; a server refusal rolls back to the snapshot. If the piggybacked rotation does not happen,
an out-of-band rekey (`noteSendAndMaybeRekey`) fires at 256 sends, unstaggered, since there is no
duel left to avoid.

The input is `sendsSinceLeafRotation`, not `sendsThisEpoch`. The latter is the application ratchet
generation of the current epoch, bounded for `max_skip`, and any epoch change resets it. Our leaf
rotates only when we commit with an UpdatePath: mls-rs forces one for a rekey, a remove, an
`end_mls` or metadata GroupContextExtensions commit and an era advance (a new leaf), but not for an
add-only commit, and a peer's commit never rotates our leaf. So the rotation counter resets on
those and survives adds and peer commits; a single per-epoch counter would never reach the
threshold in a busy group. A leave does not advance our epoch. The counter is persisted in the
conversation record; a record without the field seeds it from `sendsThisEpoch`, so no rotation is
delayed.

## Identity verification

`RccIdentityVerification` implements RCC.16 Annex C.5 (the RCC.16 §5.5 safety number) up to the
point the text defines:

* `userPairKeys` serialises the pair as
  `varint(|m1|) ‖ m1 ‖ varint(|k1|) ‖ k1 ‖ varint(|m2|) ‖ m2 ‖ varint(|k2|) ‖ k2`, where `m` is the
  normalised E.164 digit string (no `+`, `tel:` or separators) in ASCII and `k` the participant's
  whole SubjectPublicKeyInfo, so algorithm parameters are bound too.
* Users are ordered by MSISDN bytes compared unsigned (not numeric order). C.5's struct comment says
  ascending while its `if (second > first) swap` leaves the larger MSISDN first, so `PairOrder`
  offers both and defaults to the stated ascending order. Equal MSISDNs (one user, two devices) are
  tie-broken on the SPKI bytes, which C.5 does not specify, so both sides serialise identically.
* `identityHash` is SHA-512 over that serialisation, the same whichever order the users are passed.
* `identityVerificationCode` always throws. `HASH_TO_DIGITS` occurs once in C.5, at its call site,
  and is defined nowhere in v3.0 or v4.0, including the RCC.16 §1.5 cross-references. Plausible
  derivations of 80 digits from a 64-byte hash disagree (`BigInteger(1, hash) mod 10^80`, sixteen
  32-bit words each `mod 100000`, or 24-bit groups each `mod 10000`), and a wrong one would show
  honest users mismatching codes; a peer's output for a known pair, or an erratum, settles it.

`canVerify` refuses when either key is empty (the leaf did not parse) or either MSISDN normalises
to nothing.
