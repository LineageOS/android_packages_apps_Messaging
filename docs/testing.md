<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Testing

The RCS code is tested off-device, on the host JVM. Most of it cannot run there directly: the
Bugle actions, `ProviderTransport` and the carrier SIP stack need a `Context`, a bound provider,
JAIN-SIP or `SystemProperties`. The code is therefore arranged so that every decision lives in a
pure-Java class that a host test can call, and the Android-coupled code that applies the decision is
checked by source-scan guards. This page lists the suites and what each family of tests pins.

## Suites

All suites are `java_test_host` modules in `Android.bp`, in `general-tests`.

| module | sources | links | covers |
|---|---|---|---|
| `messaging-rcs-contract-host-tests` | `tests/src/org/lineageos/rcs/provider/**` | `messaging-rcs-contract-layout-host` | the provider/app AIDL contract's layout decision (`RcsContractLayout`) |
| `messaging-rcs-carrier-host-tests` | `tests/src/com/android/messaging/rcs/carrier/**`, `SourceScan`, `CarrierSipPlane` | the carrier `*-host` libraries | MSRP framing, SDP, the MSRP session layer, CPIM/IMDN, SIP digest auth, the transport bridge, and a loopback SIP + MSRP server |
| `messaging-db-schema-host-tests` | `DatabaseSchemaGuardTest`, `SourceScan` | | the database's fresh-install schema against its upgrade chain |
| `messaging-rcs-send-status-host-tests` | `tests/src/com/android/messaging/rcs/sendstatus/**`, `RcsContractKeepRuleGuardTest`, `TestNetworkWordGuardTest`, `SourceScan` | `messaging-rcs-send-status-host` | the terminal status an RCS send may leave a row in, the R8 keep rule for the contract, and that no file uses the retired test-network name |

The code under test is compiled into host libraries (`java_library_host`) that list exactly the
pure-Java sources the tests need:

| library | contents |
|---|---|
| `messaging-rcs-contract-layout-host` | `RcsContractLayout` (JDK only) |
| `messaging-rcs-msrp-host` | MSRP framing (RFC 4975) |
| `messaging-rcs-carrier-bridge-host` | `Transport`, `Message`, `RcsImsConfig`, registrar state, `CarrierTransportBridge` (no `org.json`) |
| `messaging-rcs-msrp-session-host` | SDP offer/answer, the chat-session state machine, TLS connection wrapper, MSRP sender/receiver (the JAIN-SIP session manager is excluded) |
| `messaging-rcs-sip-host` | SIP, CPIM and IMDN wire shapes (the JAIN-SIP sender/receiver are excluded) |
| `messaging-rcs-sip-digest-auth-host` | `SipDigestAuth` (RFC 2617 MD5 digest) |
| `messaging-rcs-send-status-host` | `RcsSendStatus` |

## Running

```bash
m messaging-rcs-carrier-host-tests
cd packages/apps/Messaging
J=$ANDROID_BUILD_TOP/out/soong/.intermediates/external/junit/junit/linux_glibc_common/combined/junit.jar:$ANDROID_BUILD_TOP/out/soong/.intermediates/external/hamcrest/hamcrest-core/hamcrest/linux_glibc_common/javac/hamcrest.jar
JAR=$ANDROID_BUILD_TOP/out/host/linux-x86/framework/messaging-rcs-carrier-host-tests.jar
CLASSES=$(unzip -l "$JAR" | grep -oE 'com/android/messaging/rcs/carrier/[A-Za-z0-9_/]+Test\.class' \
  | sed 's#/#.#g; s#\.class##' | sort -u)
java -cp "$JAR:$J" org.junit.runner.JUnitCore $CLASSES
```

The same pattern runs the other suites with their own jar and package.

* Source-scan guards read files relative to the working directory. They resolve a path from the
  module directory, from the tree root (`packages/apps/Messaging/<path>`) or from a directory
  directly below the module; from anywhere else they fail with "not found".
* Enumerate test classes from the jar, as above, rather than listing them: a hand-written list goes
  stale and reports success for the fraction it names.
* After adding a source to a host library, confirm the class is in the test jar
  (`unzip -l <jar> | grep <Class>`); a module that builds without a source is not evidence that the
  source is tested. Read the test jar, not the library's installed jar, which a test build does not
  refresh.

## Test families

### Carrier transport

In `messaging-rcs-carrier-host-tests`:

| tests | pin |
|---|---|
| `MsrpMessageTest` | MSRP framing (RFC 4975), including `MsrpFrameReader` over a stream |
| `SdpOfferTest`, `SdpAnswerTest`, `MsrpSessionInfoTest`, `MsrpChatSessionTest`, `MsrpTlsConnectionTest`, `CarrierMsrpSession*Test` | SDP offer/answer, the chat-session state machine, the TLS connection, MSRP SEND on both sides |
| `CpimMessageTest`, `CpimDateTimeTest`, `ImdnNotificationTest`, `IsComposingNotificationTest` | CPIM, IMDN and is-composing wire shapes |
| `CarrierTransportBridgeTest` | the glue from registrar state and receiver callbacks to `Transport.Listener` |
| `CarrierSipPlaneTest` | which bearer the SIP registrar targets, from its configured string (the decision, not its wiring) |
| `CpmSessionReportTest` | an MSRP REPORT is a delivery only with a 2xx Status, and the SR-path session answers a peer's BYE with 200; the `CpmSessionEngine` half is a source scan |
| `CarrierSipChallengeTest` | a 401 is answered in `Authorization`, a 407 in `Proxy-Authorization`, for the REGISTER and the de-REGISTER; the registrar half is a source scan |
| `MsrpChatSession_LoopbackTest`, `LoopbackSipMsrpServerTest`, `SimpleSipMessageTest`, `LoopbackCertGenTest` | the production MSRP chat-session stack against `LoopbackSipMsrpServer`, an in-process SIP + MSRP-over-TLS stand-in for a P-CSCF and MSRP relay. `LoopbackCertGen` builds its self-signed certificate's DER by hand, because the JDK's keytool classes are not accessible from unnamed modules. The server requires no client certificate, and the client authenticates it by the SHA-256 fingerprint offered in SDP (RFC 4572), with no chain validation. |

See [rcs/carrier-transport.md](rcs/carrier-transport.md).

### Provider contract

`RcsContractLayoutTest` pins `RcsContractLayout`'s decision of whether two AIDL transaction layouts
may talk, against the contract's own layout, an older one without its last method, and a later one
with a method inserted rather than appended. The Android-coupled
half, `RcsContractProbe` (reflection over the generated stub), runs on the device.
`RcsContractKeepRuleGuardTest` checks that the R8 keep rule in `proguard.flags` names the package
`IRcsProvider.aidl` declares; otherwise the stub's `TRANSACTION_*` fields are inlined away and the
probe cannot read the layout. See [rcs/provider-contract.md](rcs/provider-contract.md).

### Send status

`RcsSendStatusTest` pins that a send with a known outcome never leaves an RCS row in a status
nothing can move; `RcsSendStatusMirrorTest` reads the constants `RcsSendStatus` restates from
`MessageData` and the contract. `RcsSendStatusGuardTest` reads the production sources and checks
five properties: every writer of an outgoing RCS row records its outcome or is fed by a status
callback; the startup fixup sweeps stranded RCS rows; resend routes RCS rows away from the SMS
queue; the pending-send queue still excludes RCS rows; and one-click resend is withheld from rows
that cannot be resent. See [rcs/architecture.md](rcs/architecture.md). `InboundRedeliveryGuardTest`
checks that the receive actions do not insert a redelivered message, one whose `rcs_message_id` is
already stored, a second time.

### Database schema

`DatabaseSchemaGuardTest` checks that every table a fresh install creates is also created by a
migration, that each table's columns from `onCreate` equal upstream's version-2 columns plus every
`CREATE TABLE` and `ADD COLUMN` in the migrations past version 2, and that the declared database
version matches the last `upgradeToVersionN` step. It compares names, so it catches a missing table
or column but not a type or default mismatch.

### Source-scan guards

`DatabaseSchemaGuardTest`, `RcsContractKeepRuleGuardTest`, `RcsSendStatusGuardTest` and
`InboundRedeliveryGuardTest` assert properties of Android-coupled code by reading its source. A
source scan pins a spelling, not the property, so it is used only where the property has no other
test, and it follows these rules, which `SourceScan` implements:

* **Scan code, not prose.** `SourceScan.codeOnly` blanks comments and string contents while
  preserving offsets, so a guard cannot pass because a comment mentions the call it wants.
* **Key on the invoked method name**, never on a receiver, a variable name or a log label.
  `invocationsOf`, `bodyOf`, `declarations` and `enclosingMethod` locate calls and bodies.
  `invocationsOf` matches a substring, not an identifier (`"sendMessage("` also matches
  `resendMessage(`), so a caller that means one method checks the preceding character itself.
  `bodyOf` requires a visibility modifier; `bodyOfDeclaredAs` reads package-private methods from an
  exact declaration anchor and returns empty for an absent or non-unique anchor.
* **Zero hits fail.** A pattern that has gone stale would otherwise pass on the fraction it still
  matches, so every guard asserts that its subject is non-empty before asserting anything about it.

A negative control for a source-scan guard injects the regression by commenting the code out
rather than deleting it: a real change usually leaves the old text behind, and a raw scan is
satisfied by a commented-out call. Inject into a copy of the scanned file, not the shared tree, and
run the false-red direction too: padding an arm with comment lines must stay green.

## Debug receivers

Debug-build broadcast receivers drive the production code paths on a device. Each is exported but
checks `Build.TYPE` for `eng` or `userdebug` (`FLAG_DEBUGGABLE` is false for a system app even on a
userdebug image) and does nothing otherwise.

| receiver | actions | drives |
|---|---|---|
| `RcsDebugSendReceiver` | `com.android.messaging.debug.SEND_TEST_RCS` | a 1:1 send through `ProviderTransport`, bypassing route selection; arm extras send a displayed receipt, run an inbound message with a chosen content type through `ReceiveRcsMessageAction`, rename a group, or resolve the send gate's scheme |
| `RcsDebugComposeSendReceiver` | `com.android.messaging.debug.COMPOSE_SEND` | a 1:1 send through `InsertNewMessageAction`, the entry the compose UI uses |
| `RcsDebugFtSendReceiver`, `RcsDebugFtAcceptReceiver` | `SEND_FT`, `ACCEPT_FT` | file transfer out and acceptance of an inbound file |
| `RcsDebugGroupReceiver` | `CREATE_GROUP`, `SEND_GROUP`, `GROUP_INFO` | group creation, group send and group lookup |
| `RcsDebugCarrierDriveReceiver` | `CARRIER_START`, `CARRIER_WARM`, `CARRIER_SEND`, `CARRIER_PLAIN` | the in-app carrier transport |
| `SipDelegateDebugReceiver` | `SIPDELEGATE_*`, `SHANNON_PULL_CONFIG` | the SR path's `SipDelegateClient` and the modem configuration fetch |

## Writing a new test

* Put the decision in a pure class and test it directly; add the source to the relevant host
  library by path.
* Before writing a source-scan guard, read the existing guard for the same shape and match its
  strength. A test named for a relationship ("X before Y", "every A reaches B") needs a positional
  or structural assertion; a membership check (`contains(token)`) stays green when the relationship
  breaks and the tokens remain.
* Make zero hits fail.
* Name the state that would make the test fail and check that it is reachable; a check that cannot
  fail is not evidence.
