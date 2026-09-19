<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Testing

The RCS and MLS code is tested off-device, on the host JVM. Most of it cannot run there directly:
`MlsProviderTransport`, the Bugle actions and the carrier SIP stack need a `Context`, a bound
provider, JAIN-SIP or `SystemProperties`. The code is therefore arranged so that every decision
lives in a pure-Java class that a host test can call, and the Android-coupled code that applies the
decision is checked by source-scan guards. This page lists the suites, the fakes they share, and
what each family of tests pins.

For the MLS side, [mls/budgets.md](mls/budgets.md) and [mls/rcc16-map.md](mls/rcc16-map.md) list
the tests that cover each budget and each RCC.16 construction.

## Suites

All suites are `java_test_host` modules in `Android.bp`, in `general-tests`.

| module | sources | links | covers |
|---|---|---|---|
| `messaging-rcs-contract-host-tests` | `tests/src/org/lineageos/rcs/provider/**` | `messaging-rcs-contract-layout-host` | the provider/app AIDL contract's layout decision (`RcsContractLayout`) |
| `messaging-rcs-carrier-host-tests` | `tests/src/com/android/messaging/rcs/carrier/**`, `SourceScan`, `RccMlsBody`, `CarrierSipPlane` | the carrier `*-host` libraries, `messaging-mls-policy-host` | MSRP framing, SDP, the MSRP session layer, CPIM/IMDN, SIP digest auth, the transport bridge, and a loopback SIP + MSRP server |
| `messaging-db-schema-host-tests` | `DatabaseSchemaGuardTest`, `SourceScan` | | the database's fresh-install schema against its upgrade chain |
| `messaging-rcs-send-status-host-tests` | `tests/src/com/android/messaging/rcs/sendstatus/**`, `RcsContractKeepRuleGuardTest`, `TestNetworkWordGuardTest`, `SourceScan` | `messaging-rcs-send-status-host` | the terminal status an RCS send may leave a row in, the R8 keep rule for the contract, and that no file uses the retired test-network name outside two frozen values |
| `messaging-mls-engine-host-tests` | `tests/src/com/android/messaging/rcs/engine/**`, `SourceScan`, `RccMlsBody`, `MlsSendRouting` | `messaging-mls-policy-host` | the MLS engine's decisions, records and spec constructions, the moved transport code, and the MLS source-scan guards |

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
| `messaging-mls-policy-host` | the pure-Java part of the MLS engine module, listed file by file |

`messaging-mls-engine` itself is a device `java_library` and cannot be linked into a host test, so
`messaging-mls-policy-host` recompiles its pure sources. That list is explicit rather than a glob;
`MlsEngineHostClasspathGuardTest` fails when an engine class is neither on it nor in
`ANDROID_DEPENDENT` (`MlsEngine`, `MlsSession`, `MlsLog`, `OpenMlsEngine`, `OpenMlsNative`,
`OpenMlsSession`, and `Rcc16Der`, which imports nothing from Android but calls the native bindings
in every method), so a new engine class cannot silently go untested. The guard reads `Android.bp`
with comments blanked, so a commented-out entry does not count as listed.

Three pure classes, `RccMlsBody`, `MlsSendRouting` and `MlsCarrierTrust`, are compiled into the
engine test target instead of being promoted into the engine library. `MlsSendRouting` stays in the
app's `rcs.e2ee` package on purpose: a class that imports `com.android.messaging.rcs.engine.mls.*`
becomes part of `MlsGateCounterDurabilityGuardTest`'s provider MLS layer, and moving it into the
engine would pull `InsertNewMessageAction` into that layer and make the guard demand inventory rows
for unrelated constants.

The engine target globs its test sources, while the other targets list theirs in `Android.bp`; a
guard that must run without an `Android.bp` edit, such as `MlsConversationSchemaMigrationGuardTest`,
therefore lives in the engine suite.

## Running

```bash
m messaging-mls-engine-host-tests
cd packages/apps/Messaging
J=$ANDROID_BUILD_TOP/out/soong/.intermediates/external/junit/junit/linux_glibc_common/combined/junit.jar:$ANDROID_BUILD_TOP/out/soong/.intermediates/external/hamcrest/hamcrest-core/hamcrest/linux_glibc_common/javac/hamcrest.jar
JAR=$ANDROID_BUILD_TOP/out/host/linux-x86/framework/messaging-mls-engine-host-tests.jar
CLASSES=$(unzip -l "$JAR" | grep -oE 'com/android/messaging/rcs/engine/mls/[A-Za-z0-9]+Test\.class' \
  | sed 's#/#.#g; s#\.class##' | sort -u)
java -cp "$JAR:$J" org.junit.runner.JUnitCore $CLASSES
```

The same pattern runs the other suites with their own jar and package.

* Source-scan guards read files relative to the working directory. They resolve a path from the
  module directory, from the tree root (`packages/apps/Messaging/<path>`) or from a directory
  directly below the module; from anywhere else they fail with "not found".
* `MlsTransportDecisionCoverageGuardTest`, `MlsTransportPolicyConstantGuardTest` and
  `MlsGateCounterDurabilityGuardTest` run `tools/mls/transport-classify.py` with `python3`.
* Enumerate test classes from the jar, as above, rather than listing them: a hand-written list goes
  stale and reports success for the fraction it names.
* After adding a source to a host library, confirm the class is in the test jar
  (`unzip -l <jar> | grep <Class>`); a module that builds without a source is not evidence that the
  source is tested. Read the test jar, not the library's installed jar, which a test build does not
  refresh.

Beyond the host suites, the Rust core has its own `#[test]`s over its fixtures in
`rust/rcs_mls_ffi/testdata/` (see [mls/rust-core.md](mls/rust-core.md)), and the
`MessagingMlsSelfTest` APK runs a full local lifecycle on a device: it mints two identities under a
local CA, creates a 1:1 group, joins by Welcome and encrypts both ways, validating every certificate
with the Rust RCC.16 validator. It is a broadcast receiver
(`com.android.messaging.debug.MLS_SELFTEST`) that is inert on `user` builds.

## Shared fakes

In `tests/src/com/android/messaging/rcs/engine/mls/`:

| fake | what it is | why |
|---|---|---|
| `FakeShellPort` | a dynamic proxy over `MlsShellPort`. Only members the test stubs (`on`, `returns`) answer; any other call throws `UnsupportedOperationException` naming the member. Every call is recorded in `calls`. `stub(Class, name, answer, ...)` does the same for any other interface (`MlsSession`, `MlsTelemetry`, ...). `FakeShellPort.Log` is a recording `MlsLogSink` with `said(level, fragment)`. | A test cannot pass by quietly touching an effect it never declared, and a moved method that grows a new dependency fails loudly instead of reading a default. A proxy needs no edit when the port gains a member. |
| `SplitFixtures` | one conversation `g:grp` with MLS group `GID`, identity `+1`, peer `+2`; `port(store)` stubs the group, identity, record store, locks and session; `storeWith(edit)` and `rec(store)` build and read the stored `MlsConversationRecord`; `op(kind)` builds a pending operation | the common starting state for tests of moved transport code |
| `FakeRecords` | in-memory `MlsRecordAccess` keyed `(identity, groupId)`, with aliases keyed `(identity, conversationId)`. `unreadable` makes `get` answer `Err`; `failWrites` makes `put` refuse | drives the "record exists but cannot be read" arm, which every reader must keep apart from not-found, and the store's failure arms |
| `FakePrefs` | in-memory `MlsPrefs`; `failCommits` makes `commit()` report failure while the values still land, as on Android | drives "the stamp did not persist" arms |
| `FakeSysProps` | in-memory `MlsSysProps`; values held as strings and parsed on read, unset keys answer their default | a test sets a knob exactly as `setprop` would |
| `FakeSealedCache`, `FakePendingBodies` | in-memory `MlsSealedCacheAccess` and `MlsPendingBodyAccess`, recording every release | asserting what was released |

`SourceScan` (in `tests/src/com/android/messaging/rcs/`) is the shared source-reading helper,
described below. It is not a test class and is compiled into the engine, send-status and
database-schema suites.

## Test families

### Decisions and records

Named `<Class>Test`. They call a pure engine class directly: `MlsHealthStatesTest` and
`MlsHealthMachineTest` (the transition table and its guard order), `MlsStateChangeGateTest` (the
tier table, reconciled over every `(Tier, Guard)` pair against real calls), `MlsWindowBudgetTest`
(every declared posture behaves as declared, and no shipped budget proceeds uncounted),
`MlsFetchLedgerTest`, `MlsRecoveryPolicyTest`, `MlsDriveLoopTest`, and so on. Record classes
(`MlsConversationRecordTest`, `MlsEraBudgetRecordTest`, `MlsFetchLedgerRecordTest`,
`MlsCooldownRecordTest`, ...) pin the stored form: round trip, rejection of malformed input,
unreadable kept apart from absent, and ageing by `MlsMonotonicAge`.

The recovery rules in this family fire only on failures that cannot be produced on demand, so these
tests are their primary coverage. Many state in their javadoc which reachable state would make them
fail, so a passing test is evidence rather than a tautology.

### Spec constructions

`Rcc*Test`, `VerifiableDerivedContentTest`, `MlsAuthenticatedDataWireVectorTest`,
`MlsResentMessageTest`, `MlsAppMessageSplitTest` and similar. Each recomputes an RCC.16 or RFC 9420
construction from the specification text, or checks against captured wire bytes, rather than
round-tripping our encoder through our decoder: a matching pair of our own bugs round-trips cleanly.
Where the text leaves something undefined (the Annex C.5 digit derivation), the test pins the
refusal to guess. See [mls/rcc16-map.md](mls/rcc16-map.md).

### Moved transport code

Named `<Class>SplitTest`. `MlsProviderTransport` delegates most of its logic to engine classes that
take an `MlsShellPort` (see [mls/transport-and-port.md](mls/transport-and-port.md)). A split test
calls the engine method with a `FakeShellPort` (usually from `SplitFixtures`) and asserts the
effects on the port, the record store and the log. The transport itself cannot be constructed on the
host, so this is where those flows are tested as behaviour.

### Mirrors

`MlsProviderRpcMirrorTest` and `RcsSendStatusMirrorTest`. A pure class sometimes restates constants
whose owners are Android-coupled (AIDL Parcelables, `MessageData`), because importing the owner
would take it off the host classpath. A mirror test reads the owner's source and fails when a
constant, field or `toString` has moved.

### Source-scan guards

Named `*GuardTest` (and a few others: `MlsGuardPersistenceTest`, `MlsStateChangeTierWiringTest`).
They assert properties of Android-coupled code by reading its source. A source scan pins a spelling,
not the property, so it is used only where the property has no other test, and it follows these
rules, which `SourceScan` implements:

* **Scan code, not prose.** `SourceScan.codeOnly` blanks comments and string contents while
  preserving offsets, so a guard cannot pass because a comment mentions the call it wants, and a
  class cannot fail its own guard by documenting what it forbids.
* **Key on the invoked method name**, never on a receiver, a variable name or a log label; a label
  is a convention, and a check keyed on a convention fails silently the first time someone does not
  follow it. `invocationsOf`, `bodyOf`, `declarations` and `enclosingMethod` locate calls and
  bodies. `invocationsOf` matches a substring, not an identifier (`"sendMessage("` also matches
  `resendMessage(`), so a caller that means one method checks the preceding character itself.
  `bodyOf` requires a visibility modifier; `bodyOfDeclaredAs` reads package-private methods from an
  exact declaration anchor and returns empty for an absent or non-unique anchor.
* **Two halves.** Engine classes resolve by `Class.forName`, app classes by a recursive search under
  `src/`; `declaresMemberAnywhere` returns null, not false, for a class in neither.
* **Zero hits fail.** A pattern that has gone stale would otherwise pass on the fraction it still
  matches. Every guard asserts that its subject is non-empty before asserting anything about it
  (`theSubjectsOfThisGuardAreAllNonEmpty`, and "ZERO HITS MUST FAIL" messages).
* **Derive the enumeration from the code.** Ledger guards read their primitive names from
  `MlsFetchLedger.Primitive` and `MlsClaimLedger.AIDL_SPELLINGS`; the split's destinations are
  derived from the transport's delegates.
* **Prove the guard can fail.** Guards whose predicate is subtle carry a falsifier
  (`...GuardActuallyFails`): the predicate is run over a clean snippet, which must pass, and over
  mutated snippets, which must be caught, including one where the subject has been renamed away.
* **Read structure, not windows.** An ordering check searches each occurrence from the start of the
  body, because an `indexOf` from a landmark cannot see anything hoisted above it. Arms are read as
  brace-matched blocks, never as fixed character windows.

A negative control for a source-scan guard injects the regression by commenting the code out
rather than deleting it: a real change usually leaves the old text behind, and a raw scan is
satisfied by a commented-out call. Inject into a copy of the scanned file, not the shared tree, and
run the false-red direction too: padding an arm with comment lines must stay green.

Reading the transport. The methods the split moved out of `MlsProviderTransport` are listed, one
`Target#method` per line, in `tests/src/com/android/messaging/rcs/transport-moved.txt`, and
`SourceScan` builds its views from that list. `TransportMovedManifestGuardTest` keeps the list
complete: every entry is a static in its engine class, every engine static that takes `MlsShellPort`
first is listed or named as written in the engine, and every `/** @see Target#method */` delegate
left in the transport is listed.

| view | contents | use it for |
|---|---|---|
| `transport()` | the transport file, code only | the shell itself: what it still declares, how large it is |
| `transportDestinations()` | the engine files the listed methods live in, plus `MlsTransportTypes` | |
| `transportAndMoved()` | the transport plus every destination, code only | "does the transport's code do X anywhere" |
| `transportAsUnsplit()` / `transportUnsplitCode()` | the transport with the `MlsShellPort` adapter and every remaining delegate blanked, and each listed method appended back in its original spelling (`shell.x(` as `x(`, `cfg.` as `mCfg.`, `log.w(` as `LogUtil.w(TAG, `) | behaviour: who calls what, which ledger a call charges. Built once per JVM. |

A guard that reads the transport alone goes blind when the split moves the method it checks, and a
guard asserting absence would then pass on a subject it cannot see; a guard over the unsplit view
does not misread delegates and adapter forwarders as call sites.

Ratchets. Some guards record a number (policy constants still declared in the transport, methods
that mix a decision with an effect, undeclared transient gates) and fail when it rises. Some also
fail when it falls until the recorded number is lowered in the same change, so progress is recorded
and cannot be silently given back.

`MlsTransportDecisionCoverageGuardTest` gets its method rows from `tools/mls/transport-classify.py`.
A row's `lines` counts the non-blank lines of the method after comments and string contents are
blanked, so a comment-only edit does not move the ratchet. The only exemptions (`EXEMPT`) are the
fixture arms `armInboundHold` and `armOutboundHold`, whose decision is `buildAllowsFixtures()`, and
each exemption's claim is re-checked against the method body. The rest of the provider `e2ee`
package has its own mixed-method ratchet, so moving a mixed method into a sibling file is not read
as progress; provider classes outside `e2ee` are not covered (widening the subject would pull in a
large debug receiver). The members counted there each judge a `SharedPreferences` scan whose
enumeration needs a `Context`; their arithmetic is in `MlsMonotonicAge` and
`MlsSendRetentionPolicy.expiredMonotonic`.

`MlsGateCounterDurabilityGuardTest` defines a gate structurally: a `static final` scalar or an
`MlsConfig` field used as an operand of a relational or equality operator in the same
sub-expression, or inside the argument list of a method some engine class declares as returning
`boolean`. Every such threshold carries one `INVENTORY` row with a verdict:

| verdict | checked by |
|---|---|
| `DURABLE_RECORD` | the record class and its restart test resolve by reflection |
| `DURABLE_PREF` | a dotted `Class.PREF_` key resolves in that class |
| `TRANSIENT_DECLARED` | the row records why the counter may reset |
| `TRANSIENT_UNDECLARED` | a ratchet that stands at 0 |
| `NOT_A_GATE` | the structural rule above |
| `WIRE` | a value fixed by a spec, a peer or an ABI; refuted if it becomes order-compared, except `MlsReestablishPolicy.ERA_INITIAL`, the one legitimate order-compared row |

Site counts are pinned per constant, counting qualified references from every owner and from the
split's destinations; the counts of `mCfg.` rows are maintained by hand. The subject is the
transport, the tool's guard classes, the engine policies they delegate to, the engine owners rows
name, and the provider MLS layer: every file under the `rcs/e2ee` packages, plus every provider
source (`src/` and `selftest/src/`) that imports the engine package. `Class.CONSTANT` references
from the layer into provider classes outside it are classified per constant
(`OUTSIDE_LAYER_REFERENCES`). A pure alias, whose initialiser is nothing but a qualified reference,
needs no row if its target resolves (an engine target by reflection, a provider target by
declaration and its own row); a derived initialiser needs one. Literal eviction caps are found
through `LinkedHashMap`'s `boolean(Map.Entry)` eviction hook (`removeEldestEntry`), located by
reflection, and listed in `LITERAL_CAPS`.

`MlsFetchLedgerCoverage` rows record, per ledger caller, a coverage status with the proven and
outstanding sites, the host tests that name them, and any device recipe;
`MlsFetchLedgerCoverageGuardTest` checks them against the source. `MlsPeerGuard`'s era budget has no
debug lever: the stalled conversation's Try again is its only reset. The refusal-arm recipe for
`REBUILD` depends on the opening look being charged before `allowEraAdvance` is consulted.

#### The guard families

| family | guards | what they pin |
|---|---|---|
| every spend is charged | `MlsGetGroupInfoLedgerGuardTest`, `MlsKeyPackageClaimLedgerGuardTest`, `MlsExternalCommitLedgerGuardTest`, `MlsPeerReJoinBudgetGuardTest`, `MlsFetchLedgerRationGuardTest`, `MlsFetchLedgerCoverageGuardTest`, `MlsSelfHealChargeGuardTest` | every call that spends a server read, a peer KeyPackage, an external commit or a re-Welcome goes through its charge point, charges before it sends, and is refused without reaching the server when the budget says no; every caller constant is used; rations match what one invocation costs. See [mls/budgets.md](mls/budgets.md). |
| durability | `MlsGateCounterDurabilityGuardTest`, `MlsGuardPersistenceTest`, `MlsPerConversationStateGuardTest`, `MlsConversationTeardownGuardTest` | every gate counter is on disk or declares why not; writes are synchronous and aged by the monotonic clock; Try again reaches the stored records; every durable per-conversation store is in the teardown registry, and the ones that must outlive teardown are not; forget clears every per-conversation `Map` and `Set` field of `MlsProviderTransport` in `clearConversationState`, conversation-keyed maps by the conversation id (the scan reads code only and asserts a minimum container count) |
| nothing in the clear under a padlock | `MlsGroupPlaintextRefusalGuardTest`, `MlsMediaPlaintextRefusalGuardTest`, `MlsLocationReactionRefusalGuardTest`, `MlsUngatedOutboundSendGuardTest` | every content-bearing send (group text, media, location, reaction) asks the MLS gate first; a new content send verb fails until it is classified with a written reason |
| the transport holds no decisions | `MlsTransportDecisionCoverageGuardTest`, `MlsTransportPolicyConstantGuardTest`, `MlsEngineHostClasspathGuardTest`, `MlsStateChangeTierWiringTest` | no method in the transport mixes a decision with an effect unless exempted with the guard that covers it; no policy constant is declared there; every engine class is host-tested; `MlsPeerGuard` asks the tier table rather than restating it |
| order and verification | `MlsVerifyBeforeAdoptGuardTest`, `MlsRebuildForkRefusalGuardTest`, `MlsRebuildReCreateRefusalGuardTest`, `MlsUnacknowledgedCommitGuardTest`, `MlsMaintenanceBlindSpendGuardTest`, `MlsMaintenanceIdentityGuardTest`, `MlsUnverifiedHealthGuardTest`, `MlsHealthVerdictClaimGuardTest`, `MlsStaleGroupAnswerGuardTest`, `MlsInboundParkBoundGuardTest` | verification before the host adopts a group; a rebuild the server cannot apply is refused before anything is destroyed or charged; a commit is not discarded on an outcome that does not say whether the server took it; maintenance reconciles identity as well as era and does not spend an era advance when its fork test was refused; a health verdict reached without the divergence test is not treated as verified; a host row does not answer for a group the engine cannot read; the sites that park inbound messages are bounded and their release is checked |
| wire identity and routing | `MlsCarrierAadIdGuardTest`, `MlsEngineIdCheckWiringGuardTest`, `MlsResendReceiptRoutingGuardTest`, `MlsRenameRoutingGuardTest`, `MlsResyncCallSiteGuardTest` | the id bound into the AAD is the id on the envelope (RCC.16 §7.5.3.1); a receipt for a resend resolves to its chat row through the RCC.16 §10.3 chain; a rename of an MLS group goes out as the encrypted subject (RCC.16 §9.7.1.5); the RCC.16 §10.1.1 resync is reached only through `MlsParticipantKeyResync.planFrom` |
| state and schema | `MlsConversationSchemaMigrationGuardTest` (a new `conversations` column needs the CREATE SQL, an `upgradeToVersionN` step dispatched from `doUpgradeWithExceptions`, and `database_version` equal to the highest step; `doOnUpgrade` turns an upgrade failure into `rebuildTables`, which loses messages), `MlsContinuityMigrationGuardTest`, `MlsRejoinClearsSelfLeftGuardTest`, `MlsSelfDepartureGuardTest`, `MlsCredentialUpdateRetryGuardTest`, `MlsIconOwnershipGuardTest`, `MlsCertClockLabelGuardTest` | fresh install and upgrade produce the same `conversations` table; a legacy preference is removed only once its value is stored elsewhere; only a rejoin clears the self-leave mark; leaving is gated by the freeze alone; the RCC.16 §9.5.3 credential update does not spend its attempt on an unanswered request; one writer per producer of the conversation icon; every certificate-age figure names its clock |
| outside MLS | `DatabaseSchemaGuardTest`, `RcsContractKeepRuleGuardTest`, `RcsSendStatusGuardTest`, `RccContentTypeCaseGuardTest`, `InboundRedeliveryGuardTest` | every table a fresh install creates is also created by a migration, each table's columns from `onCreate` equal upstream's version-2 columns plus every `CREATE TABLE` and `ADD COLUMN` in the migrations past version 2, and the declared version matches the last migration; the R8 keep rule names the package the contract is in (otherwise the stub's `TRANSACTION_*` fields are inlined away and the contract probe cannot read the layout); the send-status rules below; MIME type predicates are case-insensitive; a redelivered inbound message (its `rcs_message_id` already stored) is not inserted again |

`MlsInvariantScanTest` is related but not a source scan: it tests `MlsInvariantScan`, which reads a
log and reports lines that violate runtime invariants (a drive loop reaching its cap, an unhandled
result, a dropped continuity token).

### Carrier transport

In `messaging-rcs-carrier-host-tests`:

| tests | pin |
|---|---|
| `MsrpMessageTest` | MSRP framing (RFC 4975) |
| `SdpOfferTest`, `SdpAnswerTest`, `MsrpSessionInfoTest`, `MsrpChatSessionTest`, `MsrpTlsConnectionTest`, `CarrierMsrpSession*Test` | SDP offer/answer, the chat-session state machine, the TLS connection, MSRP SEND on both sides |
| `CpimMessageTest`, `CpimDateTimeTest`, `ImdnNotificationTest`, `IsComposingNotificationTest` | CPIM, IMDN and is-composing wire shapes |
| `CarrierTransportBridgeTest` | the glue from registrar state and receiver callbacks to `Transport.Listener` |
| `CarrierSipPlaneTest` | which bearer the SIP registrar targets, from its configured string (the decision, not its wiring) |
| `CpmSessionReportTest` | an MSRP REPORT is a delivery only with a 2xx Status, and the SR-path session answers a peer's BYE with 200; the `CpmSessionEngine` half is a source scan |
| `CarrierSipChallengeTest` | a 401 is answered in `Authorization`, a 407 in `Proxy-Authorization`, for the REGISTER and the de-REGISTER; the registrar half is a source scan |
| `CarrierInboundBinaryContentTest` | the inbound content hop is binary-safe: decrypted payloads stay bytes end to end, built as real framed `RccMlsBody` payloads |
| `MsrpChatSession_LoopbackTest`, `LoopbackSipMsrpServerTest` | the production MSRP chat-session stack against `LoopbackSipMsrpServer`, an in-process SIP + MSRP-over-TLS stand-in for a P-CSCF and MSRP relay. `LoopbackCertGen` builds its self-signed certificate's DER by hand, because the JDK's keytool classes are not accessible from unnamed modules. The server requires no client certificate, and the client authenticates it by the SHA-256 fingerprint offered in SDP (RFC 4572), with no chain validation. |

See [rcs/carrier-transport.md](rcs/carrier-transport.md).

### Provider contract

`RcsContractLayoutTest` pins `RcsContractLayout`'s decision of whether two recorded AIDL transaction
layouts may talk, against layouts recovered from real builds of the contract. The Android-coupled
half, `RcsContractProbe` (reflection over the generated stub), runs on the device. See
[rcs/provider-contract.md](rcs/provider-contract.md).

### Send status

`RcsSendStatusTest` pins that a send with a known outcome never leaves an RCS row in a status
nothing can move; `RcsSendStatusMirrorTest` reads the constants `RcsSendStatus` restates.
`RcsSendStatusGuardTest` reads five production files and checks six properties: the app-owned send
records its outcome; every writer of an outgoing RCS row records its outcome or is fed by a
callback; the startup fixup sweeps stranded RCS rows; resend routes RCS rows away from the SMS
queue; the pending-send queue still excludes RCS rows; and one-click resend is withheld from rows
that cannot be resent. See [rcs/architecture.md](rcs/architecture.md#send-status).

## Device fixtures

Some recovery paths start from a state nothing produces on demand. Debug-build instruments create
those states on a device through the production code paths, not test-only copies.

### Holds

`MlsInboundHoldStore` and `MlsOutboundHoldStore` create a divergence on purpose. Both are armed
through `RcsDebugSendReceiver`, persist in their own preferences files (`mls_inbound_hold`,
`mls_outbound_hold`), check `Build.TYPE` for `eng` or `userdebug` both when armed and when used
(`FLAG_DEBUGGABLE` is false for a system app even on userdebug), and report the gap they produced
with a contiguity or consistency check.

| Fixture | Effect |
|---|---|
| inbound hold (`--es mlshold`) | Withholds inbound control messages for one conversation, putting the device behind its group. `MlsInboundHold.Mode` chooses commits only, commits and proposals (`HANDSHAKE`, the default and the only mode that replays cleanly), or both plus Welcomes (`CONTROL`). Release replays the held payloads in arrival order; `takeAll` clears the store before the replay. It holds rather than drops because the server does not backfill commits (`fetchMissedCommits` returns a GroupInfo and ratchet tree, never the intervening commits), so the held bytes are the only copy. An opt-in discard mode builds an unrecoverable gap for the rebuild path. At `MlsInboundHold.CAPACITY` (64) payloads pass through and the RCC.16 §10.8 pending queue parks future ones; identical payloads are de-duplicated by digest. |
| outbound hold (`--es mlsahead`) | Suppresses the publish of an already-applied self-update commit, so the device sits N epochs ahead of its group. Only self-update commits are held, because withholding an add or remove would split the RCS and MLS rosters. It stashes the group snapshot, with its era and epoch, before the first held commit, records each commit's `applyMlsControl` arguments in order, and refuses to arm without a usable snapshot. At `MlsOutboundHold.CAPACITY` (8) the next commit is rolled back and refused rather than published, since the server would refuse commit N while commits 1 to N-1 are unseen. Release restores the pre-commit snapshot; `restoredCleanly` succeeds only when the engine's return value and the re-read era and epoch agree, and an unreadable reading (-1) is a failure. A silent restore failure would leave the device ahead for good, recoverable only by rebuild, re-Welcome or external commit. |

### Payload corruption

`MlsPayloadCorruptor` makes a peer emit an RCC.16 §7.7.2.2 negative delivery receipt. It corrupts
the framed body before sealing, so the peer's MLS layer decrypts cleanly and only the inner payload
fails; corrupting the MLS framing or the ciphertext produces a cannot-parse reason, which emits
nothing. `LENGTH_OVERRUN` is the default, because a peer codec that reads a vector length as a byte
budget fails inside the payload; the rewritten var-int keeps its encoded width and changes by 7, so
exactly one thing is wrong. `LENGTH_UNDERRUN` and `TRUNCATE_BODY` are the other malformations, and
`BREAK_CONTAINER` (the 8-byte prefix) is a control expected not to emit. Which reason a peer's
engine maps each case to is an expectation, not a contract.

With `debug.rcs.mls_resend_tag >= 0`, `buildAadWithProbe` adds a present RCC.16 §10.3
resent-message component to the AAD on both send paths: 64 bytes of filler behind a length prefix
whose width is `debug.rcs.mls_resend_prefix` (`u8` by default). 64 exceeds 0x3F, so `u8` and var-int
prefixes differ, and the peer's error tells a wrong tag, a wrong prefix width, or both correct (an
HMAC complaint) apart. A user build ignores the tag.

The receive-side fail-next-decrypt one-shot (`PREF_FAIL_NEXT_DECRYPT`) is persisted, because the
app process often restarts between arming and arrival, and it is cleared with `commit()` before it
fires, so a crash cannot make it fire twice. The failure-reason override for the next negative
receipt is an in-memory one-shot rather than a property, so it cannot misreport a later real
failure.

### The debug receiver

`RcsDebugSendReceiver` selects an arm from its extras. `armOf` names the arm for the log and for
two gates: `NEEDS_TO` (the arm requires `--es to`) and `STATE_CHANGING` (the arm is checked against
the peer allowlist, `MlsPeerGuard.allowDebugStateChange`, for every peer it names). The allowlist
restricts only when `persist.rcs.mls_allowed_peers` is set; a device run needs it unset, or set to
the numbers the run may touch.
`assertArmVocabulary` refuses every arm if a gate list names an arm `armOf` cannot return.

`MlsTransportDiagnostics` drives production code for two probes: `seedUsageCounter` seeds the usage
counters so the next send crosses the rekey threshold, and `debugProbeDurableCooldowns` asks the
rebuild-episode and re-establish cooldowns whether they would allow their operation now, through
the same `MlsPeerGuard` calls production uses, and stamps the ones it reports as allowed. The
probe transmits nothing, but its stamp suppresses the real operation until the cooldowns are reset.

### Properties

A knob that dumps message data or key material, or relaxes a check, is read only on a debug build
(`ro.debuggable=1`): adb can set a `debug.*` property on a user build, but not an `ro.*` one. The
test is `RcsDebug.isDebugBuild()` in the app and `MlsSysProps.debuggableBuild()` in the engine, and
`DebugBuildGateGuardTest` lists the knobs it covers. `TestNetworkTrustGuardTest` holds the
compiled-in test-network root, intermediate and KDS to the same rule: they appear only in
`MlsTrustAnchors`, every read there is behind the debug-build test, and every caller of the
`MlsCarrierTrust` decisions passes `RcsDebug.isDebugBuild()`.

### Log lines

Logcat is readable over adb and lands in bug reports, so message content, secrets and unmasked
phone numbers reach it only from a line inside an if on a debug build. A number is written through
`LogMask.number` (`src/com/android/messaging/rcs/log/`, the module `messaging-log-mask`, shared
with the engine), which keeps its last four digits and, unlike `LogUtil.sanitizePII`, has no switch
a log-tag property can turn off. `MessageContentLogGuardTest` pins content per file;
`PhoneNumberLogGuardTest` scans every log call in `src/` and `engine/src/` for a number-named
identifier rendered unmasked, and checks that the Rust core masks MSISDNs and omits the epoch
authenticator. In MLS code (the engine, `rcs/e2ee` and any method named `*Mls*`) it also checks
conversation keys (a 1:1 key is `p:` and the number), message ids (a 1:1 control id such as
`mls-endmls-<number>-<ms>` embeds the number) and rosters. They go through
`MlsConversationKey.forLog`, `MlsMessageId.forLog` or `LogMask.numbers`; the key or id itself is
never rewritten. A line built outside the log call is checked where such a value is concatenated
next to a literal with a space in it, or appended after one (`.append(" scope=").append(value)`).
A call's result counts under the call's name (`canonicalKey(...)`, `scopeKey()`, `selfE164()`),
and in MLS code a stored string read back (`getString`, `optString`) counts as a key: the value
was a key or a peer when it was stored, and nothing at the log call says so.

| Property | Default | Effect |
|---|---|---|
| `debug.rcs.mls_skip_apply_ack` | false | Read live. `RcsCallbackRouter.confirmApplied` sends no confirmation, so the provider's re-offer path runs on demand. Left on, inbound is re-offered until the provider gives up and drops it. |
| `debug.rcs.mls_suppress_positive_imdn` | false | Read live. `ProviderTransport.sendImdn` sends no delivered or displayed receipt, so a peer's reaction to a failure report can be observed without a prior "delivered" for the same id. |
| `debug.rcs.mls_resend_tag`, `debug.rcs.mls_resend_prefix` | -1, `u8` | The resend-component probe above. |

## Writing a new test

* Put the decision in a pure class and test it directly; add the source to
  `messaging-mls-policy-host` (or the relevant host library) by path.
* Before writing a source-scan guard, read the existing guard for the same shape and match its
  strength. A test named for a relationship ("X before Y", "every A reaches B") needs a positional
  or structural assertion; a membership check (`contains(token)`) stays green when the relationship
  breaks and the tokens remain.
* Make zero hits fail, and give the guard a falsifier if its predicate is not obvious.
* Name the state that would make the test fail and check that it is reachable; a check that cannot
  fail is not evidence.
