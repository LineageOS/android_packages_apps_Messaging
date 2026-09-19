<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# rcs_mls_ffi — the Rust MLS core

The MLS protocol implementation (RFC 9420) and the RCC.16 spec crypto, built as a
`rust_ffi_static` and whole-static-linked into `libmlsopenmlsbridge`, the JNI face whose
Java side is `OpenMlsNative` -> `OpenMlsEngine`.

## Building

Nothing to do by hand. `m messaging` builds it, via the `librcs_mls_ffi` module in the
repository's `Android.bp`. Its dependencies are the mls-rs crates generated under
`external/mls-rs`, which carries the 0.55.2 fork and vendors the crates the platform does
not provide.

The `Cargo.toml` here is kept working for `cargo test` and for IDEs, and resolves the same
mls-rs crates Soong compiles: it names them through `external/mls-rs/android/cargo/`, whose
symlinks put the vendored `mls-rs-codec` 0.7.0, `mls-rs-codec-derive` 0.2.0 and
`mls-rs-crypto-rustcrypto` 0.22.1 in place of the repository's copies, and its
`[patch.crates-io]` sends the vendored crates' own dependencies on mls-rs crates to the same
paths. `cargo tree -d` lists no mls-rs crate twice. Soong does not read it — `Android.bp`
names the dependencies — so a change to one needs the same change to the other.
