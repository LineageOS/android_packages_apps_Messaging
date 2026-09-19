/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* OpenMLS (mls-rs) JNI bridge — marshals byte[] <-> the rcs_mls.h C ABI, exposed by
 * librcs_mls_ffi, the rust_ffi_static Soong builds from rust/rcs_mls_ffi. Java face:
 * OpenMlsNative. Handle = the ProdSession* returned as a jlong. */
#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "RcsMls"   /* rework 14.3: ONE tag across host, JNI and Rust. Mirrors MlsLog.TAG. */
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct RcsMlsSession RcsMlsSession;
typedef struct { unsigned char *data; size_t len; } RcsBytes;

extern RcsMlsSession *rcs_mls_session_start(
    const unsigned char *leaf, size_t leaf_len, const unsigned char *chain, size_t chain_len,
    const unsigned char *priv, size_t priv_len, const unsigned char *pub, size_t pub_len,
    const unsigned char *roots, size_t roots_len,
    const unsigned char *revoked, size_t revoked_len, const char *storage_dir);
extern unsigned char rcs_mls_last_status(void);
extern unsigned long long rcs_mls_take_state_bytes(void);
extern RcsBytes rcs_mls_last_aad(void);
extern int rcs_mls_set_request_message_id(const unsigned char *, size_t);
extern int rcs_mls_last_message_id_mismatch(void);
extern RcsBytes rcs_mls_last_message_id_mismatch_detail(void);
extern int rcs_mls_set_next_resent_component(const unsigned char *, size_t);
extern RcsBytes rcs_mls_last_sender_msisdn(void);
extern RcsBytes rcs_mls_generate_key_packages(RcsMlsSession *, unsigned int count);
extern RcsBytes rcs_mls_generate_last_resort_kp(RcsMlsSession *);
extern RcsBytes rcs_mls_key_package_ref(RcsMlsSession *, const unsigned char *, size_t);
extern RcsBytes rcs_mls_member_participant_keys(RcsMlsSession *, const unsigned char *, size_t);
extern RcsBytes rcs_mls_welcome_key_package_refs(RcsMlsSession *, const unsigned char *, size_t);
extern RcsBytes rcs_mls_create_group(RcsMlsSession *, unsigned int era, const unsigned char *kp, size_t kp_len, const unsigned char *gid, size_t gid_len);
extern RcsBytes rcs_mls_join(RcsMlsSession *, const unsigned char *w, size_t w_len);
extern RcsBytes rcs_mls_take_welcome_continuity_token(RcsMlsSession *, const unsigned char *gid, size_t gid_len);
extern RcsBytes rcs_mls_join_with_tree(RcsMlsSession *, const unsigned char *w, size_t w_len,
        const unsigned char *rt, size_t rt_len);
extern RcsBytes rcs_mls_join_treeless_welcome(RcsMlsSession *, const unsigned char *w, size_t w_len,
        const unsigned char *blob, size_t blob_len);
RcsBytes rcs_mls_member_validity(RcsMlsSession *s, const unsigned char *gid, size_t gid_len);
RcsBytes rcs_mls_tree_member_validity(RcsMlsSession *s, const unsigned char *tree, size_t tree_len);
RcsBytes rcs_mls_group_info_signer(RcsMlsSession *s, const unsigned char *gi, size_t gi_len);
RcsBytes rcs_mls_self_leaf_status(RcsMlsSession *s, const unsigned char *gid, size_t gid_len);
extern RcsBytes rcs_mls_external_join(RcsMlsSession *, const unsigned char *gi, size_t gi_len,
        const unsigned char *rt, size_t rt_len);
extern RcsBytes rcs_mls_encrypt(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *pt, size_t pl, const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_next_app_gen(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_process(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *w, size_t wl);
extern RcsBytes rcs_mls_epoch_auth(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_era_epoch(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_add_member(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *kp, size_t kl,
                                   const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_add_members(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                    const unsigned char *kps, size_t kl,
                                    const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_remove_member(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *sig, size_t sl,
                                      const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_remove_member_by_msisdn(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *msisdn, size_t ml,
        const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_commit_end_mls(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                       const unsigned char *aad, size_t al, unsigned char remove);
extern RcsBytes rcs_mls_group_info_ext_types(RcsMlsSession *, const unsigned char *gi, size_t gl);
extern RcsBytes rcs_mls_group_info_ext(RcsMlsSession *, const unsigned char *gi, size_t gl, unsigned short ext_type);
extern RcsBytes rcs_mls_commit_era_advance(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                           const unsigned char *aad, size_t al, unsigned int new_era);
extern RcsBytes rcs_mls_commit_group_metadata(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                              const unsigned char *aad, size_t al,
                                              const unsigned char *ik, size_t ikl,
                                              const unsigned char *ic, size_t icl,
                                              const unsigned char *sk, size_t skl,
                                              const unsigned char *sc, size_t scl);
extern RcsBytes rcs_mls_commit_icon_subject(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                            const unsigned char *aad, size_t al,
                                            const unsigned char *ic, size_t icl,
                                            const unsigned char *sc, size_t scl);
extern RcsBytes rcs_mls_group_ext(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                  unsigned short ext_type);
extern RcsBytes rcs_mls_create_group_multi(RcsMlsSession *, unsigned int era,
                                           const unsigned char *kps, size_t kl,
                                           const unsigned char *gid, size_t gl,
                                           const unsigned char *carryGi, size_t cgl,
                                           unsigned char mode);
/* No `era` parameter, deliberately — bead gfb5. The engine derives it and reports it back in the
 * bundle (slot 6 era, slot 7 welcomeAction), so there is no way for a caller to name one. */
extern RcsBytes rcs_mls_create_group_planned(RcsMlsSession *,
                                             const unsigned char *kps, size_t kl,
                                             const unsigned char *gid, size_t gl,
                                             const unsigned char *carryGi, size_t cgl,
                                             unsigned char mode);
extern RcsBytes rcs_mls_end_mls_present(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_rcs_sign(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                 const unsigned char *dc, size_t dcl);
extern RcsBytes rcs_mls_rcs_verify(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                   const unsigned char *m, size_t ml);
extern RcsBytes rcs_mls_commit_required(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_self_update(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                    const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_self_update_extpub(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                    const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_kp_is_last_resort(RcsMlsSession *, const unsigned char *kp, size_t kl);
extern RcsBytes rcs_mls_kp_inspect(RcsMlsSession *, const unsigned char *kp, size_t kl);
extern RcsBytes rcs_mls_clear_pending_proposals(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_self_leave(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                   const unsigned char *aad, size_t al);
extern RcsBytes rcs_mls_export_group_snapshot(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_restore_group_snapshot(RcsMlsSession *, const unsigned char *gid, size_t gl,
                                               const unsigned char *snap, size_t sl);
extern RcsBytes rcs_mls_external_commit_resync(RcsMlsSession *, const unsigned char *gi, size_t gil, const unsigned char *tree, size_t tl, long long remove_leaf_index);
extern RcsBytes rcs_mls_delete_group(RcsMlsSession *, const unsigned char *gid, size_t gl);
extern RcsBytes rcs_mls_process_ex(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *w, size_t wl);
extern RcsBytes rcs_mls_process_results(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *w, size_t wl, const unsigned char *ctx, size_t cl);
extern RcsBytes rcs_mls_encrypt_results(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *pt, size_t pl, const unsigned char *aad, size_t al, const unsigned char *ctx, size_t cl, bool want_key_update);
extern void rcs_mls_bytes_free(RcsBytes);
extern void rcs_mls_session_close(RcsMlsSession *);
extern void rcs_mls_set_tachyon_profile(unsigned char on);
extern void rcs_mls_set_pop_lenient(unsigned char on);
extern unsigned char rcs_mls_set_rcc16_version(unsigned char v);
extern RcsBytes rcs_mls_group_info_with_continuity(RcsMlsSession *, const unsigned char *gid, size_t gl, const unsigned char *commitment, size_t cl, unsigned char with_tree);
extern RcsBytes rcs_mls_group_info_continuity(RcsMlsSession *, const unsigned char *gi, size_t gil, unsigned short ty);

/* Copy a jbyteArray into a malloc'd buffer (caller frees). *out_len set. NULL array -> NULL/0. */
static unsigned char *jba(JNIEnv *e, jbyteArray a, size_t *out_len) {
    *out_len = 0;
    if (a == NULL) return NULL;
    jsize n = (*e)->GetArrayLength(e, a);
    if (n <= 0) return NULL;
    unsigned char *buf = (unsigned char *)malloc((size_t)n);
    if (!buf) return NULL;
    (*e)->GetByteArrayRegion(e, a, 0, n, (jbyte *)buf);
    *out_len = (size_t)n;
    return buf;
}
/* Turn an owned RcsBytes into a fresh jbyteArray + free the native buffer. */
static jbyteArray rb_to_jba(JNIEnv *e, RcsBytes b) {
    if (b.data == NULL || b.len == 0) { if (b.data) rcs_mls_bytes_free(b); return NULL; }
    jbyteArray out = (*e)->NewByteArray(e, (jsize)b.len);
    if (out) (*e)->SetByteArrayRegion(e, out, 0, (jsize)b.len, (const jbyte *)b.data);
    rcs_mls_bytes_free(b);
    return out;
}

#define JNI_FN(name) Java_com_android_messaging_rcs_engine_mls_OpenMlsNative_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeSessionStart)(
        JNIEnv *e, jclass c, jbyteArray leaf, jbyteArray chain, jbyteArray priv,
        jbyteArray pub, jbyteArray roots, jbyteArray revoked, jstring storageDir) {
    (void)c;
    /* `revoked` is the host-pushed RevokedCertificates list (CreateClientRequest field 7),
       same [u32-be len][bytes] framing as chain/roots. NULL/empty is genuine's normal state. */
    size_t ll, cl, pl, ul, rl, vl;
    unsigned char *lf = jba(e, leaf, &ll), *ch = jba(e, chain, &cl), *pv = jba(e, priv, &pl),
                  *pb = jba(e, pub, &ul), *rt = jba(e, roots, &rl), *rv = jba(e, revoked, &vl);
    const char *dir = storageDir ? (*e)->GetStringUTFChars(e, storageDir, NULL) : NULL;
    RcsMlsSession *s = rcs_mls_session_start(lf, ll, ch, cl, pv, pl, pb, ul, rt, rl, rv, vl,
                                             dir ? dir : "");
    if (dir) (*e)->ReleaseStringUTFChars(e, storageDir, dir);
    free(lf); free(ch); free(pv); free(pb); free(rt); free(rv);
    if (!s) LOGE("nativeSessionStart: rcs_mls_session_start returned NULL");
    return (jlong)(intptr_t)s;
}
/* Select the Google-Tachyon behavior profile in the shared Rust engine (lenient .4 PoP +
 * 365d KP lifetime) — the provider calls this ONCE before nativeSessionStart. Default (uncalled)
 * = the lab/RCC.16 profile, so messaging2 is unaffected. (bead gci1.12 + the cpxe KP-lifetime fix.) */
JNIEXPORT void JNICALL JNI_FN(nativeSetTachyonProfile)(JNIEnv *e, jclass c, jboolean on) {
    (void)e; (void)c;
    rcs_mls_set_tachyon_profile(on ? 1 : 0);
}
/* Escape hatch for the .4 ParticipantInformation PoP enforcement (beads 4vgz / 76bx, 2026-08-07).
 * Default (uncalled) = ENFORCE: a failed PoP rejects the peer's certificate. Pass true to restore
 * the old tolerate-and-warn behaviour without a rebuild. Exists because enforcement rejects a PEER,
 * and the evidence behind the flip only covers vendors we could measure. */
JNIEXPORT void JNICALL JNI_FN(nativeSetPopLenient)(JNIEnv *e, jclass c, jboolean on) {
    (void)e; (void)c;
    rcs_mls_set_pop_lenient(on ? 1 : 0);
}
/* RCC.16 §7.9.2 — a GroupInfo carrying the continuity-token COMMITMENT (0xF011). Under v3.0 this
 * returns the ordinary GroupInfo unchanged, so the caller may invoke it unconditionally. The TOKEN
 * (0xF010) is Welcome-only and deliberately never goes here: this GroupInfo is server-bound. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGroupInfoWithContinuity)(
        JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray commitment, jboolean withTree) {
    (void)c;
    if (!h) return NULL;
    size_t gl, cl;
    unsigned char *g = jba(e, gid, &gl), *cm = jba(e, commitment, &cl);
    RcsBytes r = rcs_mls_group_info_with_continuity((RcsMlsSession *)(intptr_t)h, g, gl, cm, cl,
                                                    withTree ? 1 : 0);
    free(g); free(cm);
    return rb_to_jba(e, r);
}
/* Read 0xF010/0xF011 out of a serialized GroupInfo. NOT version-gated: a peer that sends us
 * continuity is understood whatever we announce, and this is how the "does this server do
 * continuity at all" measurement gets made. Returns the DECODED value, or null when absent. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGroupInfoContinuity)(
        JNIEnv *e, jclass c, jlong h, jbyteArray groupInfo, jint type) {
    (void)c;
    if (!h) return NULL;
    size_t gl;
    unsigned char *g = jba(e, groupInfo, &gl);
    RcsBytes r = rcs_mls_group_info_continuity((RcsMlsSession *)(intptr_t)h, g, gl,
                                               (unsigned short)(type & 0xFFFF));
    free(g);
    return rb_to_jba(e, r);
}
/* Announce the RCC.16 SPEC VERSION the transport speaks: 30 = v3.0 (default), 40 = v4.0.
 * Returns the version now IN EFFECT — an unrecognised value is refused by the Rust side and the
 * current one kept, so a caller that gets back something other than what it sent has been told its
 * value was rejected rather than silently applied. Separate axis from the behaviour profile above:
 * profile = whose KDS/cert rules, version = which spec revision's wire shapes. */
JNIEXPORT jint JNICALL JNI_FN(nativeSetRcc16Version)(JNIEnv *e, jclass c, jint v) {
    (void)e; (void)c;
    if (v < 0 || v > 255) return (jint)rcs_mls_set_rcc16_version(0); /* out of range -> refuse, report */
    return (jint)rcs_mls_set_rcc16_version((unsigned char)v);
}
/* Status of the most recent engine op ON THIS THREAD: 0=OK 1=NO_OP 2=NOT_FOUND 3=ERR.
 * Every op used to collapse to a null byte[], so the caller could not tell a real failure from a
 * group that does not exist from an op that correctly had nothing to do. Read it IMMEDIATELY after
 * the call, on the same thread — a JNI call runs on the calling Java thread, so the thread-local is
 * that caller's. */
/* authenticated_data of the last APPLICATION message processed on this thread. Empty when the last
 * message carried none, was not an application message, or failed. Same thread/immediacy rule as
 * nativeLastStatus. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeLastAad)(JNIEnv *e, jclass c) {
    (void)c;
    return rb_to_jba(e, rcs_mls_last_aad());
}
/* §7.5.3.1 (bead n3bh): tell the engine which message_id this request is for, BEFORE
 * nativeProcess/nativeProcessEx. RCC.16 puts AAD construction and validation in the engine and
 * gives the host only a message_id via the request context — this is that id. Passing null CLEARS
 * it, which SKIPS the check rather than failing it, so an unmigrated caller keeps working. */
JNIEXPORT jint JNICALL JNI_FN(nativeSetRequestMessageId)(JNIEnv *e, jclass c, jbyteArray mid) {
    (void)c;
    if (!mid) return (jint)rcs_mls_set_request_message_id(NULL, 0);
    size_t ml; unsigned char *m = jba(e, mid, &ml);
    jint r = (jint)rcs_mls_set_request_message_id(m, ml);
    if (m) free(m);
    return r;
}
/* Non-zero when the last application message's AAD message_id did NOT match the id above. This is
 * RCC.16 MessageIdMismatch(9) computed where the spec puts it — in the engine. */
JNIEXPORT jint JNICALL JNI_FN(nativeLastMessageIdMismatch)(JNIEnv *e, jclass c) {
    (void)e; (void)c;
    return (jint)rcs_mls_last_message_id_mismatch();
}
/* The (expected, actual) pair behind a mismatch as expected\\0actual, for logging. Empty if none. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeLastMessageIdMismatchDetail)(JNIEnv *e, jclass c) {
    (void)c;
    return rb_to_jba(e, rcs_mls_last_message_id_mismatch_detail());
}
/* Place a §10.3 resent-message component in the NEXT AAD the engine builds on this thread, instead
 * of the absent 0x00. One-shot. Supplying a COMPONENT is not the removed seam coming back: §10.3
 * makes the component the host's to choose while the AAD around it stays the engine's to build.
 * Used by the resent-component probe (bead uckg). */
JNIEXPORT jint JNICALL JNI_FN(nativeSetNextResentComponent)(JNIEnv *e, jclass c, jbyteArray comp) {
    (void)c;
    if (!comp) return (jint)rcs_mls_set_next_resent_component(NULL, 0);
    size_t cl; unsigned char *x = jba(e, comp, &cl);
    jint r = (jint)rcs_mls_set_next_resent_component(x, cl);
    if (x) free(x);
    return r;
}
/* The CERTIFIED MSISDN of the leaf that signed the last application message on this thread (bead
 * h6e0). Empty means UNKNOWN — never a match. Same thread/immediacy rule as nativeLastAad. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeLastSenderMsisdn)(JNIEnv *e, jclass c) {
    (void)c;
    return rb_to_jba(e, rcs_mls_last_sender_msisdn());
}
JNIEXPORT jint JNICALL JNI_FN(nativeLastStatus)(JNIEnv *e, jclass c) {
    (void)e; (void)c;
    return (jint)rcs_mls_last_status();
}
/* Largest group-state record written on this thread since the last read, in bytes; 0 if none.
 * READING CLEARS IT, so a host recording after every op cannot double-count a previous write.
 * Bucketed host-side into Bugle.Mls.ZinniaStateSize (rework 13.3). */
JNIEXPORT jlong JNICALL JNI_FN(nativeTakeStateBytes)(JNIEnv *e, jclass c) {
    (void)e; (void)c;
    return (jlong)rcs_mls_take_state_bytes();
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGenerateKeyPackages)(JNIEnv *e, jclass c, jlong h, jint count) {
    (void)c; if (!h) return NULL;
    return rb_to_jba(e, rcs_mls_generate_key_packages((RcsMlsSession *)(intptr_t)h, (unsigned)count));
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGenerateLastResortKp)(JNIEnv *e, jclass c, jlong h) {
    (void)c; if (!h) return NULL;
    return rb_to_jba(e, rcs_mls_generate_last_resort_kp((RcsMlsSession *)(intptr_t)h));
}
/* bead 10uu: per-leaf (index, MSISDN, participant-key SPKI) for the §10.1.1 resync. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeMemberParticipantKeys)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_member_participant_keys((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
/* bead 5qat: the KeyPackageRef of one published KP, and the refs a Welcome is sealed to. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeKeyPackageRef)(JNIEnv *e, jclass c, jlong h, jbyteArray kp) {
    (void)c; if (!h) return NULL;
    size_t kl; unsigned char *k = jba(e, kp, &kl);
    jbyteArray r = rb_to_jba(e, rcs_mls_key_package_ref((RcsMlsSession *)(intptr_t)h, k, kl));
    free(k); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeWelcomeKeyPackageRefs)(JNIEnv *e, jclass c, jlong h, jbyteArray w) {
    (void)c; if (!h) return NULL;
    size_t wl; unsigned char *wb = jba(e, w, &wl);
    jbyteArray r = rb_to_jba(e, rcs_mls_welcome_key_package_refs((RcsMlsSession *)(intptr_t)h, wb, wl));
    free(wb); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCreateGroup)(JNIEnv *e, jclass c, jlong h, jint era, jbyteArray kp, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t kl; unsigned char *k = jba(e, kp, &kl);
    size_t gl = 0; unsigned char *g = gid ? jba(e, gid, &gl) : NULL;   // gid null/empty ⟹ mint a fresh UUID
    jbyteArray r = rb_to_jba(e, rcs_mls_create_group((RcsMlsSession *)(intptr_t)h, (unsigned)era, k, kl, g, gl));
    free(k); if (g) free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeJoin)(JNIEnv *e, jclass c, jlong h, jbyteArray w) {
    (void)c; if (!h) return NULL;
    size_t wl; unsigned char *wb = jba(e, w, &wl);
    jbyteArray r = rb_to_jba(e, rcs_mls_join((RcsMlsSession *)(intptr_t)h, wb, wl));
    free(wb); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeJoinWithTree)(JNIEnv *e, jclass c, jlong h,
        jbyteArray w, jbyteArray rt) {
    (void)c; if (!h) return NULL;
    size_t wl, rl; unsigned char *wb = jba(e, w, &wl), *r = jba(e, rt, &rl);
    jbyteArray res = rb_to_jba(e, rcs_mls_join_with_tree((RcsMlsSession *)(intptr_t)h, wb, wl, r, rl));
    free(wb); free(r); return res;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeJoinTreelessWelcome)(JNIEnv *e, jclass c, jlong h,
        jbyteArray w, jbyteArray blob) {
    (void)c; if (!h) return NULL;
    size_t wl, bl; unsigned char *wb = jba(e, w, &wl), *bb = jba(e, blob, &bl);
    jbyteArray res = rb_to_jba(e, rcs_mls_join_treeless_welcome((RcsMlsSession *)(intptr_t)h, wb, wl, bb, bl));
    free(wb); free(bb); return res;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeMemberValidity)(JNIEnv *e, jclass c, jlong h,
        jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray res = rb_to_jba(e, rcs_mls_member_validity((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return res;
}
/* Read-only: which leaf SIGNED a GroupInfo (bead htos). On the server's stored anchor that is
 * the member whose Commit produced the epoch — fork attribution, from the GroupInfo bytes alone. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGroupInfoSigner)(JNIEnv *e, jclass c, jlong h,
        jbyteArray gi) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gi, &gl);
    jbyteArray res = rb_to_jba(e, rcs_mls_group_info_signer((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return res;
}
/* Read-only: leaf certificate windows in a SERIALIZED ratchet tree (bead htos). Takes the
 * SERVER's tree bytes; loads nothing and mutates nothing, unlike nativeJoinWithTree. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeTreeMemberValidity)(JNIEnv *e, jclass c, jlong h,
        jbyteArray tree) {
    (void)c; if (!h) return NULL;
    size_t tl; unsigned char *t = jba(e, tree, &tl);
    jbyteArray res = rb_to_jba(e, rcs_mls_tree_member_validity((RcsMlsSession *)(intptr_t)h, t, tl));
    free(t); return res;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSelfLeafStatus)(JNIEnv *e, jclass c, jlong h,
        jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray res = rb_to_jba(e, rcs_mls_self_leaf_status((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return res;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeExternalJoin)(JNIEnv *e, jclass c, jlong h,
        jbyteArray gi, jbyteArray rt) {
    (void)c; if (!h) return NULL;
    size_t gl, rl; unsigned char *g = jba(e, gi, &gl), *r = jba(e, rt, &rl);
    jbyteArray res = rb_to_jba(e, rcs_mls_external_join((RcsMlsSession *)(intptr_t)h, g, gl, r, rl));
    free(g); free(r); return res;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncrypt)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray pt, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl, pl; unsigned char *g = jba(e, gid, &gl), *p = jba(e, pt, &pl);
    size_t al = 0; unsigned char *a = aad ? jba(e, aad, &al) : NULL;   // aad null/empty ⟹ empty AAD
    jbyteArray r = rb_to_jba(e, rcs_mls_encrypt((RcsMlsSession *)(intptr_t)h, g, gl, p, pl, a, al));
    free(g); free(p); if (a) free(a); return r;
}
/* nativeNextAppGen(handle, groupId) -> 4-byte big-endian generation the next encrypt will stamp.
 * The RCC.16 body header's uint32 must carry this SAME value — genuine increments the framing counter
 * and sender_data.generation in lockstep, and v308 rejects a message whose stamped generation
 * disagrees with the framed counter. Java frames with this before encrypting. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeNextAppGen)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_next_app_gen((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
/* nativeEpochAuth(handle, groupId) -> 32-byte epoch_authenticator of the group's CURRENT epoch
 * (RCC.16 §7.11 Epoch-Authenticator). Wired for the SendMessage fwvc.f12 Epoch-Authenticator part. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEpochAuth)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_epoch_auth((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
/* nativeEraEpoch(handle, groupId) -> 12-byte [era u32 BE][epoch u64 BE] for the group's CURRENT
 * state. era stamps the SendMessage Era-ID part (not hardcoded 1); epoch is logged for the
 * incorrect-epoch-authenticator (local-vs-server epoch lag) diagnosis. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEraEpoch)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_era_epoch((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
/* ---- S2 group-mutation + resync + delete + status-tagged process ---- */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeAddMember)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray kp, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl, kl, al; unsigned char *g = jba(e, gid, &gl), *k = jba(e, kp, &kl), *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_add_member((RcsMlsSession *)(intptr_t)h, g, gl, k, kl, a, al));
    free(g); free(k); free(a); return r;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeAddMembers)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray kps, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl, kl, al;
    unsigned char *g = jba(e, gid, &gl), *k = jba(e, kps, &kl), *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_add_members((RcsMlsSession *)(intptr_t)h, g, gl, k, kl, a, al));
    free(g); free(k); free(a); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeRemoveMember)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray sig, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl, sl, al; unsigned char *g = jba(e, gid, &gl), *s = jba(e, sig, &sl), *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_remove_member((RcsMlsSession *)(intptr_t)h, g, gl, s, sl, a, al));
    free(g); free(s); free(a); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeRemoveMemberByMsisdn)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray msisdn, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl, ml, al; unsigned char *g = jba(e, gid, &gl), *m = jba(e, msisdn, &ml), *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_remove_member_by_msisdn((RcsMlsSession *)(intptr_t)h, g, gl, m, ml, a, al));
    free(g); free(m); free(a); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCommitEndMls)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray aad, jboolean rm) {
    (void)c; if (!h) return NULL;
    size_t gl, al; unsigned char *g = jba(e, gid, &gl), *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_commit_end_mls((RcsMlsSession *)(intptr_t)h, g, gl, a, al, rm ? 1 : 0));
    free(g); free(a); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGroupInfoExtTypes)(JNIEnv *e, jclass c, jlong h, jbyteArray gi) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gi, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_group_info_ext_types((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCommitEraAdvance)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray aad, jint newEra) {
    (void)c; if (!h) return NULL;
    size_t gl, al; unsigned char *g = jba(e, gid, &gl), *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_commit_era_advance((RcsMlsSession *)(intptr_t)h, g, gl, a, al, (unsigned int)newEra));
    free(g); free(a); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCommitGroupMetadata)(JNIEnv *e, jclass c, jlong h,
        jbyteArray gid, jbyteArray aad, jbyteArray ik, jbyteArray ic, jbyteArray sk, jbyteArray sc) {
    (void)c; if (!h) return NULL;
    size_t gl, al, ikl, icl, skl, scl;
    unsigned char *g = jba(e, gid, &gl), *a = jba(e, aad, &al);
    unsigned char *i1 = jba(e, ik, &ikl), *i2 = jba(e, ic, &icl);
    unsigned char *s1 = jba(e, sk, &skl), *s2 = jba(e, sc, &scl);
    jbyteArray r = rb_to_jba(e, rcs_mls_commit_group_metadata((RcsMlsSession *)(intptr_t)h,
            g, gl, a, al, i1, ikl, i2, icl, s1, skl, s2, scl));
    free(g); free(a); free(i1); free(i2); free(s1); free(s2); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCommitIconSubject)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray aad, jbyteArray ic, jbyteArray sc) {
    (void)c; if (!h) return NULL;
    size_t gl, al, icl, scl;
    unsigned char *g = jba(e, gid, &gl), *a = jba(e, aad, &al), *i = jba(e, ic, &icl), *s2 = jba(e, sc, &scl);
    jbyteArray r = rb_to_jba(e, rcs_mls_commit_icon_subject((RcsMlsSession *)(intptr_t)h, g, gl, a, al, i, icl, s2, scl));
    free(g); free(a); free(i); free(s2); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCreateGroupMulti)(JNIEnv *e, jclass c, jlong h,
        jint era, jbyteArray kps, jbyteArray gidOverride, jbyteArray carryGroupInfo,
        jint advanceMode) {
    (void)c; if (!h) return NULL;
    size_t kl, gl, cgl;
    unsigned char *k = jba(e, kps, &kl), *g = jba(e, gidOverride, &gl);
    unsigned char *cg = jba(e, carryGroupInfo, &cgl);
    jbyteArray r = rb_to_jba(e, rcs_mls_create_group_multi((RcsMlsSession *)(intptr_t)h,
            (unsigned int)era, k, kl, g, gl, cg, cgl, (unsigned char)advanceMode));
    free(k); free(g); free(cg); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCreateGroupPlanned)(JNIEnv *e, jclass c, jlong h,
        jbyteArray kps, jbyteArray gidOverride, jbyteArray carryGroupInfo, jint advanceMode) {
    (void)c; if (!h) return NULL;
    size_t kl, gl, cgl;
    unsigned char *k = jba(e, kps, &kl), *g = jba(e, gidOverride, &gl);
    unsigned char *cg = jba(e, carryGroupInfo, &cgl);
    jbyteArray r = rb_to_jba(e, rcs_mls_create_group_planned((RcsMlsSession *)(intptr_t)h,
            k, kl, g, gl, cg, cgl, (unsigned char)advanceMode));
    free(k); free(g); free(cg); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGroupExt)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jint extType) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_group_ext((RcsMlsSession *)(intptr_t)h, g, gl, (unsigned short)extType));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeTakeWelcomeContinuityToken)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_take_welcome_continuity_token((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGroupInfoExt)(JNIEnv *e, jclass c, jlong h, jbyteArray gi, jint extType) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gi, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_group_info_ext((RcsMlsSession *)(intptr_t)h, g, gl, (unsigned short)extType));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEndMlsPresent)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_end_mls_present((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeRcsSign)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray dc) {
    (void)c; if (!h) return NULL;
    size_t gl, dcl; unsigned char *g = jba(e, gid, &gl), *d = jba(e, dc, &dcl);
    jbyteArray r = rb_to_jba(e, rcs_mls_rcs_sign((RcsMlsSession *)(intptr_t)h, g, gl, d, dcl));
    free(g); free(d); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeRcsVerify)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray msg) {
    (void)c; if (!h) return NULL;
    size_t gl, ml; unsigned char *g = jba(e, gid, &gl), *m = jba(e, msg, &ml);
    jbyteArray r = rb_to_jba(e, rcs_mls_rcs_verify((RcsMlsSession *)(intptr_t)h, g, gl, m, ml));
    free(g); free(m); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCommitRequired)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_commit_required((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSelfUpdate)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    size_t al; unsigned char *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_self_update((RcsMlsSession *)(intptr_t)h, g, gl, a, al));
    free(g); free(a); return r;
}
/* As nativeSelfUpdate, but publishes a GroupInfo carrying external_pub and returns the post-commit
 * ratchet_tree in record 6. external_pub is COMMITTER-produced, so without this our groups' stored
 * GroupInfo can never support a resync external commit. */
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSelfUpdateExtPub)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    size_t al; unsigned char *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_self_update_extpub((RcsMlsSession *)(intptr_t)h, g, gl, a, al));
    free(g); free(a); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeKpIsLastResort)(JNIEnv *e, jclass c, jlong h, jbyteArray kp) {
    (void)c; if (!h) return NULL;
    size_t kl; unsigned char *k = jba(e, kp, &kl);
    jbyteArray r = rb_to_jba(e, rcs_mls_kp_is_last_resort((RcsMlsSession *)(intptr_t)h, k, kl));
    free(k); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeClearPendingProposals)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_clear_pending_proposals((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeKpInspect)(JNIEnv *e, jclass c, jlong h, jbyteArray kp) {
    (void)c; if (!h) return NULL;
    size_t kl; unsigned char *k = jba(e, kp, &kl);
    jbyteArray r = rb_to_jba(e, rcs_mls_kp_inspect((RcsMlsSession *)(intptr_t)h, k, kl));
    free(k); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSelfLeave)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray aad) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    size_t al; unsigned char *a = jba(e, aad, &al);
    jbyteArray r = rb_to_jba(e, rcs_mls_self_leave((RcsMlsSession *)(intptr_t)h, g, gl, a, al));
    free(g); free(a); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeExportGroupSnapshot)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_export_group_snapshot((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeRestoreGroupSnapshot)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray snap) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    size_t sl; unsigned char *sn = jba(e, snap, &sl);
    jbyteArray r = rb_to_jba(e, rcs_mls_restore_group_snapshot((RcsMlsSession *)(intptr_t)h, g, gl, sn, sl));
    free(g); free(sn); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeExternalCommitResync)(JNIEnv *e, jclass c, jlong h, jbyteArray gi, jbyteArray tree, jlong removeLeafIndex) {
    (void)c; if (!h) return NULL;
    size_t gil, tl; unsigned char *g = jba(e, gi, &gil), *t = jba(e, tree, &tl);
    jbyteArray r = rb_to_jba(e, rcs_mls_external_commit_resync((RcsMlsSession *)(intptr_t)h, g, gil, t, tl, (long long)removeLeafIndex));
    free(g); free(t); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeDeleteGroup)(JNIEnv *e, jclass c, jlong h, jbyteArray gid) {
    (void)c; if (!h) return NULL;
    size_t gl; unsigned char *g = jba(e, gid, &gl);
    jbyteArray r = rb_to_jba(e, rcs_mls_delete_group((RcsMlsSession *)(intptr_t)h, g, gl));
    free(g); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeProcessEx)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray wire) {
    (void)c; if (!h) return NULL;
    size_t gl, wl; unsigned char *g = jba(e, gid, &gl), *w = jba(e, wire, &wl);
    jbyteArray r = rb_to_jba(e, rcs_mls_process_ex((RcsMlsSession *)(intptr_t)h, g, gl, w, wl));
    free(g); free(w); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncryptResults)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray pt, jbyteArray aad, jbyteArray ctx, jboolean wantKeyUpdate) {
    (void)c; if (!h) return NULL;
    size_t gl, pl, al, cl;
    unsigned char *g = jba(e, gid, &gl), *p = jba(e, pt, &pl), *a = jba(e, aad, &al), *x = jba(e, ctx, &cl);
    jbyteArray r = rb_to_jba(e, rcs_mls_encrypt_results((RcsMlsSession *)(intptr_t)h, g, gl, p, pl, a, al, x, cl, wantKeyUpdate == JNI_TRUE));
    free(g); free(p); free(a); free(x); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeProcessResults)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray wire, jbyteArray ctx) {
    (void)c; if (!h) return NULL;
    size_t gl, wl, cl; unsigned char *g = jba(e, gid, &gl), *w = jba(e, wire, &wl), *x = jba(e, ctx, &cl);
    jbyteArray r = rb_to_jba(e, rcs_mls_process_results((RcsMlsSession *)(intptr_t)h, g, gl, w, wl, x, cl));
    free(g); free(w); free(x); return r;
}
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeProcess)(JNIEnv *e, jclass c, jlong h, jbyteArray gid, jbyteArray wire) {
    (void)c; if (!h) return NULL;
    size_t gl, wl; unsigned char *g = jba(e, gid, &gl), *w = jba(e, wire, &wl);
    jbyteArray r = rb_to_jba(e, rcs_mls_process((RcsMlsSession *)(intptr_t)h, g, gl, w, wl));
    free(g); free(w); return r;
}
JNIEXPORT void JNICALL JNI_FN(nativeSessionClose)(JNIEnv *e, jclass c, jlong h) {
    (void)e; (void)c; if (h) rcs_mls_session_close((RcsMlsSession *)(intptr_t)h);
}
