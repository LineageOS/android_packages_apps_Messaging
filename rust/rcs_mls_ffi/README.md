# rcs_mls_ffi — the Rust MLS core

The MLS protocol implementation (RFC 9420) and the RCC.16 spec crypto, built as a
`rust_ffi_static` and whole-static-linked into `libmlsopenmlsbridge`, the JNI face whose
Java side is `OpenMlsNative` -> `OpenMlsEngine`.

## Building

Nothing to do by hand. `m messaging` builds it, via the `librcs_mls_ffi` module in the
repository's `Android.bp`. Its dependencies are the mls-rs crates generated under
`external/mls-rs`, which carries the 0.55.2 fork and its vendored closure.

The `Cargo.toml` here is kept working for `cargo test` and for IDEs; its `mls-rs` path
dependency points at `external/mls-rs/mls-rs`, the same sources Soong compiles. Soong does
not read it — `Android.bp` names the dependencies — so a change to one needs the same
change to the other.
