<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Design documentation

These documents describe how RCS and its end-to-end encryption are built into Messaging: what each
part is for, the contracts between the parts, and the reasons behind the choices a reviewer would
otherwise have to reconstruct from the code. They assume familiarity with Android and with
RFC 9420 (MLS). Code comments stay short and point here for the rationale.

## Reading order

1. [rcs/architecture.md](rcs/architecture.md): the two routes to an RCS network, how one is chosen
   per subscription, the send and receive paths, send status, and the database additions.
2. [rcs/provider-contract.md](rcs/provider-contract.md): the AIDL interface to a provider app, the
   append-only ordering rule and the runtime layout check.
3. [mls/overview.md](mls/overview.md): who owns MLS (the app encrypts, the provider carries), the
   engine module and the native core.
4. [mls/transport-and-port.md](mls/transport-and-port.md): how the app's MLS layer is split between
   Android bindings and engine decisions.
5. [mls/group-lifecycle.md](mls/group-lifecycle.md), then
   [mls/health-and-recovery.md](mls/health-and-recovery.md): the normal life of an encrypted
   conversation, then what happens when it diverges.
6. The remaining topics as needed.

## Index

### RCS

| Document | Content |
|---|---|
| [rcs/architecture.md](rcs/architecture.md) | App and provider split, the transport seam, discovery and binding, route selection, send gating, inbound dispatch, send status, schema |
| [rcs/provider-contract.md](rcs/provider-contract.md) | `IRcsProvider` and `IRcsProviderCallback`, parcelables, ordinal ordering rule, layout digest and pairing, delivery confirmation |
| [rcs/groups.md](rcs/groups.md) | Group conversations: mapping, creation, sending, inbound routing, group events, management, local mirrors, receipts |
| [rcs/carrier-transport.md](rcs/carrier-transport.md) | The in-app carrier SIP/MSRP transport: processes, SR and DR registration, Digest authentication, pager and session messaging, send status |

### MLS

| Document | Content |
|---|---|
| [mls/overview.md](mls/overview.md) | Ownership (the app encrypts, the provider carries), the engine module, the JNI bridge |
| [mls/transport-and-port.md](mls/transport-and-port.md) | `MlsProviderTransport`, `MlsShellPort`, the delegates, and what stays in the transport and why |
| [mls/group-lifecycle.md](mls/group-lifecycle.md) | Establish, join (including a Welcome without a ratchet tree), add, remove, leave, eras |
| [mls/health-and-recovery.md](mls/health-and-recovery.md) | Health states and transitions, self-heal, the drive loop, the RCC.16 §10 failed-to-decrypt and resend ladder |
| [mls/downgrade.md](mls/downgrade.md) | `end_mls`, reviving in place or in a new era, fast re-upgrade |
| [mls/credentials.md](mls/credentials.md) | Certificates, the 30-day floor, RCC.16 §9.5.3 Self-Update, identity refresh |
| [mls/metadata.md](mls/metadata.md) | Encrypted group subject and icon, FileInfo keys, commitments |
| [mls/budgets.md](mls/budgets.md) | Fetch and claim ledgers, the era-advance budget, rate limits |
| [mls/rcc16-map.md](mls/rcc16-map.md) | RCC.16 section to class and method |
| [mls/rust-core.md](mls/rust-core.md) | The FFI surface, storage, RCC.16 validation in Rust |

### Testing

| Document | Content |
|---|---|
| [testing.md](testing.md) | Host test suites, fakes, and the source-scan guards: what each family pins |
