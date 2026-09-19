<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Messaging

The LineageOS SMS/MMS/RCS messaging app. It is a fork of the Android Open Source
Project's Messaging app, extended with a Rich Communication Services (RCS)
implementation and with end-to-end encryption based on the Messaging Layer
Security protocol (MLS, RFC 9420).

## What is in here

| Path | What it holds |
|---|---|
| `src/` | The app: AOSP Messaging, plus `src/com/android/messaging/rcs/` |
| `src/com/android/messaging/rcs/carrier/` | Carrier RCS — SIP registration, MSRP sessions, file transfer, IMS integration |
| `src/com/android/messaging/rcs/e2ee/` | The MLS group lifecycle as the app sees it: when to create, join, repair or downgrade a conversation |
| `engine/` | `messaging-mls-engine` — the protocol-level MLS and GSMA RCC.16 logic, with no Android dependencies so it can be host-tested |
| `rust/` | The Rust MLS core and its C FFI surface, built on [mls-rs](https://github.com/awslabs/mls-rs) |
| `jni/` | The JNI bridge from `engine/` down to the Rust core |
| `aidl/` | `messaging-rcs-contract-aidl` — the interface an RCS provider implements (see below) |
| `tests/` | Host tests. `atest messaging-mls-engine-host-tests` and siblings |

## Routes to an RCS network

Two paths reach an RCS network, and this app supports both:

* **Carrier RCS**, implemented in-tree under `rcs/carrier/`. It talks to the
  carrier's own RCS service over SIP and MSRP using the device's IMS stack. No
  extra app is involved.

* **A provider app**, bound over the AIDL interface in `aidl/`
  (`org.lineageos.rcs.provider.IRcsProvider`). The app discovers a provider,
  binds to it, and exchanges messages, group state, capabilities and MLS
  envelopes across that boundary. No protobuf or vendor type crosses it.

Nothing in this repository, in AOSP, or in LineageOS implements
`IRcsProvider`. The interface exists so that a provider can be supplied
out-of-tree. With no provider installed, the app falls back to carrier RCS if
the carrier supports it, and to SMS/MMS otherwise. That is the default, and it
is a supported configuration — the provider path is opt-in.

## End-to-end encryption

MLS is implemented per GSMA RCC.16. `engine/` owns group state, commit and
proposal handling, credential validation and the recovery ladder; the Rust core
under `rust/` owns the RFC 9420 protocol itself, via mls-rs.

E2EE is enabled by default and can be turned off per subscription in the RCS
settings. When a conversation cannot be encrypted — an unsupported peer, a failed
key fetch — the app says so in the UI rather than silently sending in the clear.

## Building

The app builds as part of a LineageOS product; no extra setup is needed for an
ordinary build:

```
breakfast <device>
m messaging
```

The Rust MLS core is built from source by Soong: `rust/rcs_mls_ffi` is a
`rust_ffi_static` linked against the mls-rs crates in `external/mls-rs`. Changing
anything under `rust/` is an ordinary source edit — the next `m messaging` rebuilds it.

## Testing

```
atest messaging-mls-engine-host-tests
atest messaging-rcs-carrier-host-tests
atest messaging-rcs-send-status-host-tests
atest messaging-rcs-contract-host-tests
atest messaging-db-schema-host-tests
```

These are host tests — they need no device. Much of `engine/` is deliberately
free of Android dependencies for exactly this reason.

## Documentation

Design documentation is in `docs/`; start with `docs/README.md`. Behaviour that
is awkward to reach from the UI is reachable through `debug.rcs.*` system
properties and debug broadcasts, described in `docs/testing.md`.

## Licence

Apache 2.0. See `LICENSE` and `NOTICE`.
