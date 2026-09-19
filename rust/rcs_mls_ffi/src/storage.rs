// Copyright (C) 2026 The LineageOS Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// M2: pure-Rust file-based GroupStateStorage (no C deps → keeps the clean cross-compile).
// Persists group state + prior epochs under a per-identity dir; survives process restart.
use std::fs;
use std::path::PathBuf;
use zeroize::Zeroizing;
use mls_rs_core::group::{EpochRecord, GroupState, GroupStateStorage};
use mls_rs_core::key_package::{KeyPackageData, KeyPackageStorage};
use mls_rs_core::error::IntoAnyError;
use mls_rs::mls_rs_codec::{MlsEncode, MlsDecode};

#[derive(Debug)]
pub struct StorageErr(pub String);
impl IntoAnyError for StorageErr {}
impl From<std::io::Error> for StorageErr {
    fn from(e: std::io::Error) -> Self { StorageErr(e.to_string()) }
}

// ---------------------------------------------------------------------------------------------
// ONE GROUP, ONE FILE, ONE RENAME
// ---------------------------------------------------------------------------------------------
//
// `GroupStateStorage::write` is contractually ONE unit: the group state and the epoch records it
// references must become visible together or not at all. We used to write each of them with its own
// temp-file + rename — individually atomic, collectively not — and in the WRONG ORDER: the state
// landed first, so a crash between the renames left a group state referencing epoch records that did
// not exist yet. That group can no longer decrypt at the epochs it claims to know.
//
// mls-rs cannot repair that. The group loads, reports its epoch, and fails per message.
//
// So the whole record now lives in ONE file written with ONE rename, which is atomic on any POSIX
// filesystem. The cost is rewriting the epoch set on every write; the set is small and bounded (see
// EPOCH_RETENTION), and correctness here is not negotiable — this layer's entire durability argument
// is "one engine call is all-or-nothing".
//
// The KeyPackage half needs no work: mls-rs already writes group state BEFORE deleting the consumed
// KeyPackage (vendor state_repo.rs:225-235), so a crash between them leaves a durable group and a
// stale KP file — an unconsumed secret we will never use again. The dangerous order (delete then
// write, which would lose the ability to process the Welcome at all) does not occur upstream. This
// is why there is no cross-storage transaction here: there is nothing for it to protect.

/// File magic for the combined record. Also tags an exported snapshot, so a snapshot taken by an
/// older build (a bare state blob) is still recognisable on restore.
const RECORD_MAGIC: &[u8; 4] = b"RCSG";

/// Bump only for a change the decoder cannot absorb.
///
/// 2 added the per-epoch wall-clock stamp the AGE bound needs (see [`EPOCH_RETENTION_MS_V3_0`]). 1
/// is the unstamped original: still READ, never written. Those records hold the epoch secrets of
/// live groups on the fleet — refusing to read one would destroy decryption material outright,
/// which is enormously worse than the bug the stamp fixes. A v1 record cannot be retro-stamped
/// (its bytes are already on disk and say nothing about when they were written), so its epochs are
/// age-UNKNOWN for the rest of their lives; see [`EpochEntry::stored_ms`] for what that means for
/// pruning. The first [`GroupRecord::encode`] of a loaded record writes it back as v2, and since
/// the record is always written WHOLE that is every write.
const RECORD_VERSION: u8 = 2;

/// The last unstamped version. Read-only.
const RECORD_VERSION_UNSTAMPED: u8 = 1;

/// How many epoch records to keep per group — the COUNT half of the retention rule.
///
/// Epochs exist to decrypt messages that arrive out of order across a commit, so what this really
/// bounds is "how far behind may a peer be". Unbounded growth is not an option now that the whole
/// set is rewritten per write, so a cap has to exist; the only question is where.
///
/// **64, from measurement.** It
/// was 16, which was a guess, and the guess was under the requirement rather than over it:
///
///  - EPOCHS/DAY on real groups ran 5.5–12.7 (four dated endpoints, three groups), and day one of a
///    group's life is the top of the band. Three days at the busiest measured rate is 38.
///  - EVERY commit advances an epoch — adds, removes, rekeys, self-updates, and every PEER commit —
///    and application traffic alone forces them: Google Messages rotates every ~40 of its OWN sends
///    (measured twice), ours every `REKEY_AFTER_SENDS` (host-side). Members SUM, so three chatty
///    Google Messages peers is ~15/day before anyone touches the roster.
///  - THE LAG is what actually decides it, not the rate: a peer sat at epoch 3 while its group ran
///    to 45 — a 42-epoch spread in 32 minutes. For the members at 45 to read what it sent from 3,
///    42 prior epochs had to still be there. At 16 they were not.
///
/// So 16 was already below ordinary use, and 64 clears three days at the busy end with ~1.7×
/// headroom and covers the measured 42. It is not a licence to retain LONGER than RCC.16 asks:
/// [`epoch_retention_ms`] still cuts at three days, and the intent is that the AGE bound decides in
/// the ordinary case while this one only bites in a churn storm (which is a defect —
/// not a workload to size for; the 08-01 burst hit 56 epochs/HOUR and nothing sane covers that).
///
/// **The binding cost is I/O, not disk.** `store()` re-serialises the WHOLE record per write and
/// `write_to_storage` runs on every application message encrypted AND every message processed, not
/// just on commits — so this constant is a per-message write multiplier. Measured at 4 members:
/// ~839 B per retained epoch, so a whole record is 16 KB at 16, 56 KB at 64, ~217 KB at 256. That
/// is why the count was not simply demoted to a far-above safety valve: 256 is rejected on the write
/// path, not on storage.
///
/// **It does not survive an ERA advance**, and that bounds what any value here can buy: for a group
/// that changes era, §6.1.1's three days is zero whatever this says. **Two sites clear it, not one**
/// — the advancer's, in `create_group_carry`'s `gid_override` arm, and every other
/// member's, in `purge_prior_epochs_on_join` on all five join paths — a peer reaches a new era by
/// Welcome, never by a commit chain. Both are load-bearing and were falsified by removing them:
/// without the first the advance itself fails `apply: InvalidEpoch`; without the second the joiner
/// carries the old era's archive forward and mls-rs refuses its next commit. The
/// deeper reason is that a new era is a new group at the SAME group id, so the epoch id restarts and
/// `(group_id, epoch_id)` addresses two different secrets across the boundary — the old entries are
/// not merely unused, they would shadow the live ones. **Nothing here is the control**: the record
/// is gone before either bound is consulted.
///
/// **v4.0 needs more than a bigger number.** The window becomes 30 days = 165–381 epochs at the
/// measured band, i.e. hundreds of KB rewritten per message. Raising the cap for v4.0 requires first
/// taking the epoch archive off the per-message write path (a plain in-epoch message produces empty
/// `epoch_inserts` AND `epoch_updates`, so it need not rewrite the epoch half) and must land in the
/// same change as the deletion CEILING — a 30-day floor without the ceiling is a security
/// regression shipped in the name of conformance.
const EPOCH_RETENTION: usize = 64;

/// RCC.16 v3.0 §6.1.1 (p.23): *"clients shall keep the secrets of the previous Epoch for decrypting
/// messages for at least 3 days."*
///
/// A COUNT is not a DURATION, which is what [`EPOCH_RETENTION`] alone was: any fixed count is a
/// different amount of time on a chatty group than on a quiet one, and says nothing about three
/// days either way. The failure mode was silent — a message arriving slightly late became
/// permanently undecryptable and surfaced as a failed-to-decrypt receipt, which names nothing about
/// retention. Sizing the count so the two agree in ordinary use is [`EPOCH_RETENTION`]'s job; this
/// is what makes the promise.
const EPOCH_RETENTION_MS_V3_0: u64 = 3 * 24 * 60 * 60 * 1000;

/// RCC.16 v4.0 §6.1.1 raises the same requirement to **30 days**, and adds a ceiling the v3.0 text
/// does not have: retain for 30 days, then *immediately delete*.
///
/// v3.0 states a FLOOR only ("at least"), so pruning at 3 days conforms and retains the least
/// decryptable material. v4.0's ceiling is a `shall DELETE`, and meeting it needs more than this
/// constant — see [`FileGroupStateStorage::prune_expired_epochs`].
const EPOCH_RETENTION_MS_V4_0: u64 = 30 * 24 * 60 * 60 * 1000;

/// The age window in force, selected by the RCC.16 version the transport announced.
///
/// Reads [`crate::rcc16::rcc16_version`] rather than hard-coding three days, so the v4.0 raise is
/// the version assignment that already exists and not a second edit to this file. Every prune site
/// goes through here.
fn epoch_retention_ms() -> u64 {
    retention_ms_for_rcc16_version(crate::rcc16::rcc16_version())
}

/// Split out from [`epoch_retention_ms`] so the mapping is testable WITHOUT touching the process-wide
/// version atomic, which the rest of the suite reads concurrently.
fn retention_ms_for_rcc16_version(v: u8) -> u64 {
    if v >= crate::rcc16::RCC16_V4_0 { EPOCH_RETENTION_MS_V4_0 } else { EPOCH_RETENTION_MS_V3_0 }
}

/// Wall clock, milliseconds since the UNIX epoch. 0 if the clock is before 1970.
///
/// The DEVICE clock, and there is no trusted alternative down here: the callers are mls-rs trait
/// methods, which have nowhere to pass one from. A clock that jumps BACKWARDS makes an epoch look
/// younger and retains it longer, which is the safe direction; a clock that jumps FORWARDS can
/// expire an epoch early, which is why the age comparison saturates and why the count cap — which
/// no clock can perturb — stays.
fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// One retained prior-epoch record: the secret material, and WHEN we stored it.
#[derive(Clone)]
struct EpochEntry {
    /// Wall-clock ms at which this epoch record was first stored, or `None` for UNKNOWN.
    ///
    /// UNKNOWN is not a synonym for old, and this is the whole migration decision. An epoch read out
    /// of a v1 record — or out of the legacy per-file layout — carries no stamp, and nothing on disk
    /// can date it after the fact. Both readings that put a number here are wrong and destructive in
    /// the same direction: treating it as infinitely old deletes live decryption material on the
    /// first write after the upgrade, and stamping it with "now" invents a date the record never
    /// had. So an unknown-age entry is never evicted BY AGE; only [`EPOCH_RETENTION`], the count cap,
    /// may evict it, exactly as before this format existed. The set is self-draining: every new
    /// epoch is stamped, and the unstamped ones fall off the bottom of the cap as the group advances.
    ///
    /// The cost is that a group which stops committing keeps its unstamped epochs indefinitely. That
    /// is fine under v3.0, whose requirement is a floor. It is the one population that cannot satisfy
    /// v4.0's 30-day deletion ceiling, and the answer there is to stamp them at first load under
    /// v4.0 (bounding them from that moment forward) — a decision that belongs with the switch, not
    /// ahead of it.
    stored_ms: Option<u64>,
    data: Vec<u8>,
}

/// One group's persisted state: the group blob plus its retained epoch records.
#[derive(Default)]
struct GroupRecord {
    state: Option<Vec<u8>>,
    /// Ordered so trimming keeps the NEWEST epochs and `max_epoch_id` is the last key.
    epochs: std::collections::BTreeMap<u64, EpochEntry>,
}

impl GroupRecord {
    fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(4096);
        out.extend_from_slice(RECORD_MAGIC);
        out.push(RECORD_VERSION);
        let st = self.state.as_deref().unwrap_or(&[]);
        out.extend_from_slice(&(st.len() as u32).to_be_bytes());
        out.extend_from_slice(st);
        out.extend_from_slice(&(self.epochs.len() as u32).to_be_bytes());
        for (id, e) in &self.epochs {
            out.extend_from_slice(&id.to_be_bytes());
            // 0 is the on-disk spelling of UNKNOWN (see EpochEntry::stored_ms) — it is also a real
            // instant, 1970-01-01, which no device clock we will ever meet reports as "now".
            out.extend_from_slice(&e.stored_ms.unwrap_or(0).to_be_bytes());
            out.extend_from_slice(&(e.data.len() as u32).to_be_bytes());
            out.extend_from_slice(&e.data);
        }
        out
    }

    /// Strict decode. A truncated or mistyped record is an ERROR, never a silently empty group: an
    /// empty group reads as "never joined" and would send us down the create path for a conversation
    /// we are already in.
    fn decode(raw: &[u8]) -> Result<Self, StorageErr> {
        let mut p = 0usize;
        let need = |p: usize, n: usize| -> Result<(), StorageErr> {
            if p + n > raw.len() {
                Err(StorageErr(format!("group record truncated at {p} (+{n} of {})", raw.len())))
            } else { Ok(()) }
        };
        need(p, 5)?;
        if &raw[0..4] != RECORD_MAGIC {
            return Err(StorageErr("group record: bad magic".into()));
        }
        let version = raw[4];
        if version != RECORD_VERSION && version != RECORD_VERSION_UNSTAMPED {
            return Err(StorageErr(format!("group record: version {version} unsupported")));
        }
        p = 5;
        need(p, 4)?;
        let slen = u32::from_be_bytes([raw[p], raw[p + 1], raw[p + 2], raw[p + 3]]) as usize;
        p += 4;
        need(p, slen)?;
        let state = if slen == 0 { None } else { Some(raw[p..p + slen].to_vec()) };
        p += slen;
        need(p, 4)?;
        let n = u32::from_be_bytes([raw[p], raw[p + 1], raw[p + 2], raw[p + 3]]) as usize;
        p += 4;
        let mut epochs = std::collections::BTreeMap::new();
        for _ in 0..n {
            need(p, 8)?;
            let mut idb = [0u8; 8];
            idb.copy_from_slice(&raw[p..p + 8]);
            let id = u64::from_be_bytes(idb);
            p += 8;
            // v1 has no stamp field at all — not a zero stamp, no field. Its epochs are age-UNKNOWN.
            let stored_ms = if version == RECORD_VERSION_UNSTAMPED { None } else {
                need(p, 8)?;
                let mut tb = [0u8; 8];
                tb.copy_from_slice(&raw[p..p + 8]);
                p += 8;
                match u64::from_be_bytes(tb) { 0 => None, ms => Some(ms) }
            };
            need(p, 4)?;
            let len = u32::from_be_bytes([raw[p], raw[p + 1], raw[p + 2], raw[p + 3]]) as usize;
            p += 4;
            need(p, len)?;
            epochs.insert(id, EpochEntry { stored_ms, data: raw[p..p + len].to_vec() });
            p += len;
        }
        Ok(GroupRecord { state, epochs })
    }

    /// THE RETENTION RULE. An epoch record goes when it is OLDER than `retention_ms`, **or** when
    /// the count cap still needs it gone. Returns `(aged_out, capped_out)`.
    ///
    /// Age first, count second, and the order is not cosmetic: the count cap keeps the NEWEST ids, so
    /// running it first could evict a young epoch to make room for an ancient one that the age pass
    /// was about to delete anyway.
    ///
    /// Both bounds are needed and neither subsumes the other. RCC.16 §6.1.1 asks for a DURATION;
    /// [`EPOCH_RETENTION`] answers "the per-write rewrite must stay small". An unknown-age entry is
    /// invisible to the age pass by construction — see [`EpochEntry::stored_ms`].
    fn trim_at(&mut self, now_ms: u64, retention_ms: u64) -> (usize, usize) {
        let start = self.epochs.len();
        self.epochs.retain(|_, e| match e.stored_ms {
            // saturating_sub: a device clock that moved BACKWARDS since the stamp yields age 0 and
            // the entry is KEPT. Over-retention is recoverable; deleting an epoch secret is not.
            Some(t) => now_ms.saturating_sub(t) <= retention_ms,
            None => true,
        });
        let aged = start - self.epochs.len();
        let after_age = self.epochs.len();
        while self.epochs.len() > EPOCH_RETENTION {
            let oldest = *self.epochs.keys().next().unwrap();
            self.epochs.remove(&oldest);
        }
        (aged, after_age - self.epochs.len())
    }
}

#[derive(Clone)]
pub struct FileGroupStateStorage { dir: PathBuf }

impl FileGroupStateStorage {
    /// NO FILESYSTEM SIDE EFFECT — a read verb never writes.
    ///
    /// This used to `create_dir_all` here, and the constructor is called from READ-shaped verbs
    /// (`next_app_gen`, `export_group_snapshot`, the restore path). A directory is not a default
    /// row, but it IS a write from a read path, and it is exactly the shape that trips the
    /// read-only scope enforced below. The write paths (`store`, `put`) create the directory
    /// themselves, so nothing was gained by doing it twice.
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }
    fn hexid(gid: &[u8]) -> String {
        gid.iter().map(|b| format!("{b:02x}")).collect()
    }
    /// The combined record. One file, one rename.
    fn record_path(&self, gid: &[u8]) -> PathBuf {
        self.dir.join(format!("g_{}.bin", Self::hexid(gid)))
    }
    /// LEGACY layout, read-only. Devices in the field hold live groups in these files, so the
    /// migration must be lossless: read falls back to them, and the next write folds them into the
    /// combined record and removes them.
    fn state_path(&self, gid: &[u8]) -> PathBuf {
        self.dir.join(format!("s_{}.bin", Self::hexid(gid)))
    }
    #[cfg(test)]
    fn epoch_path(&self, gid: &[u8], eid: u64) -> PathBuf {
        self.dir.join(format!("e_{}_{:020}.bin", Self::hexid(gid), eid))
    }
    /// Atomic write: temp file + rename (avoids partial-write corruption).
    fn write_atomic(&self, path: &PathBuf, data: &[u8]) -> Result<(), StorageErr> {
        // A read verb must not write. Both stores funnel every write through their own
        // write_atomic, which is what makes the read/write partition enforceable rather than
        // documented — and the known read-path bug in Google Messages is precisely a write that
        // appeared on a read path and was invisible.
        if crate::ffi::writes_forbidden() {
            let msg = format!("write refused: {} is inside a READ-ONLY scope (item 1.7)",
                path.display());
            crate::ffi::alog!("storage: {msg}");
            return Err(StorageErr(msg));
        }
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, data)?;
        fs::rename(&tmp, path)?;
        Ok(())
    }

    /// Load a group's record, falling back to the legacy per-file layout.
    fn load(&self, gid: &[u8]) -> Result<GroupRecord, StorageErr> {
        let p = self.record_path(gid);
        if p.exists() {
            return GroupRecord::decode(&fs::read(&p)?);
        }
        // Legacy: s_<gid>.bin + e_<gid>_<epoch>.bin
        let mut rec = GroupRecord::default();
        let sp = self.state_path(gid);
        if sp.exists() {
            rec.state = Some(fs::read(&sp)?);
        }
        let prefix = format!("e_{}_", Self::hexid(gid));
        if let Ok(rd) = fs::read_dir(&self.dir) {
            for ent in rd.flatten() {
                let name = ent.file_name().to_string_lossy().to_string();
                if let Some(rest) = name.strip_prefix(&prefix) {
                    if let Some(num) = rest.strip_suffix(".bin") {
                        if let Ok(id) = num.parse::<u64>() {
                            if let Ok(data) = fs::read(ent.path()) {
                                // The legacy layout has nowhere to put a stamp, so these arrive
                                // age-UNKNOWN for the same reason a v1 record's epochs do.
                                rec.epochs.insert(id, EpochEntry { stored_ms: None, data });
                            }
                        }
                    }
                }
            }
        }
        Ok(rec)
    }

    /// Persist a record as ONE atomic unit, then retire any legacy files for the same group.
    ///
    /// Legacy cleanup happens only AFTER the combined record is durable, so an interrupted migration
    /// loses nothing: the next load either finds the new record or falls back to the legacy files
    /// that are still there.
    fn store(&self, gid: &[u8], rec: &mut GroupRecord) -> Result<(), StorageErr> {
        fs::create_dir_all(&self.dir)?;
        let (aged, _capped) = rec.trim_at(now_ms(), epoch_retention_ms());
        if aged > 0 {
            // Only the AGE drops are logged. A capped drop happens on essentially every write once a
            // group is past 16 epochs and says nothing; an age drop is the retention window actually
            // firing, and it is the line to reach for when a late message fails to decrypt.
            crate::ffi::alog!(
                "storage: dropped {aged} prior-epoch record(s) older than {} h (RCC.16 §6.1.1 \
                 retention window, v{} announced)",
                epoch_retention_ms() / 3_600_000, crate::rcc16::rcc16_version());
        }
        let blob = rec.encode();
        // Bugle.Mls.ZinniaStateSize — the cheapest state-bloat regression detector
        // there is. Noted on every write, bucketed and recorded host-side.
        crate::ffi::note_state_write(blob.len());
        self.write_atomic(&self.record_path(gid), &blob)?;
        self.remove_legacy(gid);
        Ok(())
    }

    fn remove_legacy(&self, gid: &[u8]) {
        let _ = fs::remove_file(self.state_path(gid));
        let prefix = format!("e_{}_", Self::hexid(gid));
        if let Ok(rd) = fs::read_dir(&self.dir) {
            for ent in rd.flatten() {
                let name = ent.file_name().to_string_lossy().to_string();
                if name.starts_with(&prefix) && name.ends_with(".bin") {
                    let _ = fs::remove_file(ent.path());
                }
            }
        }
    }

    /// Snapshot the current group STATE blob (`s_<hexid>.bin`) for a defer-until-ACK rollback. Returns
    /// the raw bytes, or `None` if the group is not persisted. Paired with {@link restore_state}: capture
    /// BEFORE an optimistic self-update, restore if the server rejects the commit — so the local
    /// epoch never drifts ahead of the server.
    pub fn export_state(&self, gid: &[u8]) -> Option<Vec<u8>> {
        // Snapshots the WHOLE record (state + epochs), not just the state blob. The old snapshot
        // captured the state alone and relied on restore_state deleting epochs above a high-water
        // mark to undo the rest — which cannot restore an epoch record the rollback needs to bring
        // BACK. Restoring the full record is exact, and it is tagged with the record magic so a
        // snapshot taken by an older build is still recognised (see restore_state).
        let rec = self.load(gid).ok()?;
        if rec.state.is_none() && rec.epochs.is_empty() { return None; }
        Some(rec.encode())
    }

    /// Inherent (non-trait) max-epoch scan — usable without importing the maybe-async
    /// `GroupStateStorage` trait. Same logic as the trait's `max_epoch_id`; returns 0 if none.
    pub fn current_max_epoch(&self, gid: &[u8]) -> u64 {
        self.load(gid).ok()
            .and_then(|r| r.epochs.keys().next_back().copied())
            .unwrap_or(0)
    }

    /// Restore a group STATE snapshot: rewrite `s_<hexid>.bin` from `state` and delete every epoch
    /// record NEWER than `keep_max_epoch` (the drift's `e_` files from the un-ACKed self-update), so the
    /// group is exactly back at its pre-commit epoch. Best-effort.
    pub fn restore_state(&self, gid: &[u8], state: &[u8], keep_max_epoch: u64) {
        let mut rec = if state.len() >= 4 && &state[0..4] == RECORD_MAGIC {
            // A full snapshot from export_state: restore it verbatim. Exact, because it carries the
            // epoch records as they stood, not merely a high-water mark to delete above.
            match GroupRecord::decode(state) {
                Ok(r) => r,
                Err(_) => return,   // never half-restore; leave the group as it is
            }
        } else {
            // Legacy snapshot: a bare group-state blob taken by an older build. Keep the previous
            // semantics — replace the state, drop epochs above the caller's high-water mark.
            let mut r = self.load(gid).unwrap_or_default();
            r.state = Some(state.to_vec());
            r
        };
        rec.epochs.retain(|id, _| *id <= keep_max_epoch);
        let _ = self.store(gid, &mut rec);
    }

    /// Delete ALL persisted state for a group: `s_<hexid>.bin` + every `e_<hexid>_*.bin`. Used by
    /// `ProdSession::delete_group` for the cptg AHEAD-discard / PHOENIX reset. Best-effort (ignores
    /// missing files); a stale over-advanced group must be gone before an external-commit re-join.
    pub fn delete_group(&self, gid: &[u8]) {
        let _ = fs::remove_file(self.record_path(gid));
        self.remove_legacy(gid);
    }

    /// Apply the AGE half of the retention rule to `gid` without the engine having written anything.
    /// Returns how many epoch records went. Writes only if at least one did.
    ///
    /// Pruning otherwise happens inside [`FileGroupStateStorage::store`], i.e. only when the group is
    /// active — and an active group is not the one that over-retains. A group that stops committing
    /// keeps its last epoch set for as long as it stays quiet, which conforms to v3.0 §6.1.1 (a
    /// FLOOR: "at least 3 days") and does not conform to v4.0 §6.1.1's added ceiling ("then
    /// immediately delete"). This is the entry point that closes that gap, and it is deliberately
    /// NOT wired to a caller yet: the ceiling only binds once a transport announces v4.0, which none
    /// does (`rcc16.rs` — `RCC16_V3_0` is still the default), and wiring a periodic secret-deleting
    /// sweep before it binds is a way to lose decryption material for no conformance gain.
    pub fn prune_expired_epochs(&self, gid: &[u8], now_ms: u64) -> usize {
        let mut rec = match self.load(gid) { Ok(r) => r, Err(_) => return 0 };
        let (aged, _capped) = rec.trim_at(now_ms, epoch_retention_ms());
        if aged == 0 { return 0; }   // a no-op sweep must not write
        if self.store(gid, &mut rec).is_err() { return 0; }
        aged
    }

    /// Drop every archived PRIOR EPOCH for `gid`, keeping the group state itself. Returns how many
    /// were dropped. Call this when JOINING a group from a Welcome.
    ///
    /// WHY THIS EXISTS — it is a joiner-stranding bug, and the mechanism is a bookkeeping gap rather
    /// than anything cryptographic.
    ///
    /// mls-rs archives the epoch it is LEAVING on every commit (`state_repo::insert`), and that
    /// insert REFUSES a non-contiguous id:
    ///
    /// ```text
    /// if let Some(expected_id) = self.find_max_id().await?.map(|id| id + 1) {
    ///     if epoch_id != expected_id { return Err(MlsError::InvalidEpoch) }
    /// }
    /// ```
    ///
    /// `find_max_id` is our `max_epoch_id`, i.e. the largest key in this record's `epochs` map. The
    /// MLS group id does NOT change when a member is removed and re-added inside one era, so a
    /// rejoining member keeps the archive from its PREVIOUS membership. Rejoin by Welcome at epoch
    /// 17 with an archive that stops at 12, and the very next commit tries to archive epoch 17 while
    /// insert expects 13 — refused, and refused as InvalidEpoch, which reads exactly like a state
    /// fork:
    ///
    /// ```text
    /// process_ex apply FAIL (3612B) msg_epoch=Some(17) pre=17 -> status 8: InvalidEpoch
    /// ```
    ///
    /// A commit at epoch 17 rejected by a group at epoch 17, with an epoch authenticator matching
    /// the committer's byte for byte. Device-proven 2026-08-19 as a stated prediction before it was
    /// run: the member re-added at 17 failed at 17, while a member genuinely behind at 15 parked the
    /// same commit FROM_FUTURE, which is correct.
    ///
    /// This is why the re-add "remedy" always seemed to work and then strand somebody: the Welcome
    /// really does heal the member, and the next commit breaks it again — permanently, because the
    /// server does not backfill.
    ///
    /// CLEARING IS CORRECT, NOT MERELY EXPEDIENT. mls-rs skips the contiguity check outright when
    /// the archive is empty (`if let Some(..)`), so a joiner starts clean the way a first-time
    /// joiner already does. The entries being dropped are the secrets of epochs on the branch we
    /// have just LEFT; after a rejoin we are on the tree the Welcome put us in, and nothing from
    /// before it is usable. The one real cost is that a late-arriving application message from one
    /// of those old epochs no longer decrypts — which was already true for every epoch we missed
    /// while removed, and is a far smaller loss than a conversation that can never take another
    /// commit.
    pub fn purge_epochs(&self, gid: &[u8]) -> usize {
        let mut rec = match self.load(gid) { Ok(r) => r, Err(_) => return 0 };
        let n = rec.epochs.len();
        if n == 0 { return 0; }
        rec.epochs.clear();
        // Best-effort: a failed write leaves the stale archive in place, which is the pre-fix
        // behaviour rather than a new failure mode. Say so instead of returning a count that
        // claims a purge that did not happen.
        if self.store(gid, &mut rec).is_err() {
            crate::ffi::alog!("storage: purge_epochs FAILED to write for the joined group — the \
                stale prior-epoch archive is still there and the next commit will be refused \
                InvalidEpoch");
            return 0;
        }
        n
    }
}

impl GroupStateStorage for FileGroupStateStorage {
    type Error = StorageErr;

    fn state(&self, gid: &[u8]) -> Result<Option<Zeroizing<Vec<u8>>>, StorageErr> {
        Ok(self.load(gid)?.state.map(Zeroizing::new))
    }

    fn epoch(&self, gid: &[u8], eid: u64) -> Result<Option<Zeroizing<Vec<u8>>>, StorageErr> {
        Ok(self.load(gid)?.epochs.remove(&eid).map(|e| Zeroizing::new(e.data)))
    }

    /// ONE UNIT. Read the current record, apply the state and every epoch insert/update to it, and
    /// commit the whole thing with a single rename.
    ///
    /// The previous implementation renamed the state, then each epoch, in that order — so a crash
    /// mid-way published a group state whose epoch records did not exist yet. There is no recovery
    /// from that: the group loads and then fails to decrypt at the epochs it claims.
    fn write(
        &mut self,
        state: GroupState,
        epoch_inserts: Vec<EpochRecord>,
        epoch_updates: Vec<EpochRecord>,
    ) -> Result<(), StorageErr> {
        let mut rec = self.load(&state.id)?;
        // THE RULE — an UPDATE that matches zero rows aborts the WHOLE write.
        //
        // This used to chain inserts and updates into one loop and `insert` both, so an update for
        // an epoch record that did not exist silently CREATED it and returned Ok. That is not a
        // harmless generosity: an update names a record the engine believes it already wrote, so a
        // miss means the engine's model of storage and storage itself have diverged. Completing the
        // write then persists a group state built on that false belief, and the divergence surfaces
        // later as a decrypt failure at an epoch we claim to hold — with nothing left to point at.
        //
        // Checked BEFORE anything is applied, so the abort leaves the record untouched rather than
        // half-updated. Nothing is written until every update has matched.
        for e in &epoch_updates {
            if !rec.epochs.contains_key(&e.id) {
                let msg = format!(
                    "epoch update for id {} matched zero rows (group has {} epoch record(s)) — \
                     aborting the whole write per invariant 68",
                    e.id, rec.epochs.len());
                crate::ffi::alog!("storage: {msg}");
                return Err(StorageErr(msg));
            }
        }
        rec.state = Some(state.data.to_vec());
        let now = now_ms();
        for e in epoch_inserts {
            rec.epochs.insert(e.id, EpochEntry { stored_ms: Some(now), data: e.data.to_vec() });
        }
        for e in epoch_updates {
            // An UPDATE MUST NOT RESTAMP. mls-rs inserts an epoch record when the group LEAVES that
            // epoch and updates it afterwards as the ratchet advances over late messages — so the
            // insert is the moment the epoch became a *previous* epoch, which is exactly what
            // §6.1.1's clock measures. Restamping would silently extend retention for every epoch
            // that keeps receiving traffic, and would put a date on an unknown-age migrated entry
            // that its bytes never justified. The loop above the state assignment has already
            // proved every id here exists, so a miss is impossible rather than
            // ignored.
            if let Some(slot) = rec.epochs.get_mut(&e.id) { slot.data = e.data.to_vec(); }
        }
        self.store(&state.id, &mut rec)
    }

    fn max_epoch_id(&self, gid: &[u8]) -> Result<Option<u64>, StorageErr> {
        Ok(self.load(gid)?.epochs.keys().next_back().copied())
    }
}

/// File-based KeyPackageStorage — persists the SECRET keys (init_key, leaf_node_key) of every
/// KeyPackage we generate + upload to KDS, keyed by the KeyPackageRef. Without this, mls-rs uses
/// the DEFAULT in-memory repo: the session that generates+uploads a KP dies, its secrets die with
/// it, and a LATER session's `process_welcome` cannot find the KP the peer's Welcome was HPKE-sealed
/// to → `WelcomeKeyPackageNotFound` (device-observed 2026-07-23: iPhone added us to a group, we got
/// a real 600B Welcome but could not join). mls-rs auto-`delete`s the entry once a KP is consumed by
/// a successful join (RFC 9420: KPs are single-use). Files sit alongside the group-state s_/e_ files
/// in the SAME per-identity storage_dir; the `kp_` prefix keeps them namespaced.
#[derive(Clone)]
pub struct FileKeyPackageStorage { dir: PathBuf }

impl FileKeyPackageStorage {
    /// No filesystem side effect — see [`FileGroupStateStorage::new`].
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }
    fn hexid(id: &[u8]) -> String {
        id.iter().map(|b| format!("{b:02x}")).collect()
    }
    fn kp_path(&self, id: &[u8]) -> PathBuf {
        self.dir.join(format!("kp_{}.bin", Self::hexid(id)))
    }
    /// The TOMBSTONE for a consumed/expired key package.
    ///
    /// A sibling file rather than a flag inside the record, because the record is the thing being
    /// removed: the whole point is that the private material is gone while the FACT of its removal
    /// survives.
    fn kp_tombstone_path(&self, id: &[u8]) -> PathBuf {
        self.dir.join(format!("kp_{}.dead", Self::hexid(id)))
    }
    /// `<trusted_ms>,<device_ms>` — TWO clocks, and the distinction is load-bearing.
    ///
    /// The TRUSTED clock is what expiry comparisons use. The DEVICE clock is recorded for forensics
    /// only and is never compared, because a device clock can move backwards (a user setting the
    /// date, an NTP correction) and an expiry decided against one that jumped is an expiry that
    /// happens at an arbitrary moment. Keeping both means a confusing purge can be explained after
    /// the fact without letting the untrustworthy one drive the decision.
    fn tombstone_payload(trusted_ms: u64, device_ms: u64) -> String {
        format!("{trusted_ms},{device_ms}")
    }
    fn tombstone_trusted_ms(raw: &str) -> Option<u64> {
        raw.split(',').next().and_then(|s| s.trim().parse::<u64>().ok())
    }
    fn write_atomic(&self, path: &PathBuf, data: &[u8]) -> Result<(), StorageErr> {
        // A read verb must not write. Both stores funnel every write through their own
        // write_atomic, which is what makes the read/write partition enforceable rather than
        // documented — and the known read-path bug in Google Messages is precisely a write that
        // appeared on a read path and was invisible.
        if crate::ffi::writes_forbidden() {
            let msg = format!("write refused: {} is inside a READ-ONLY scope (item 1.7)",
                path.display());
            crate::ffi::alog!("storage: {msg}");
            return Err(StorageErr(msg));
        }
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, data)?;
        fs::rename(&tmp, path)?;
        Ok(())
    }

    /// Tombstone anything past `expiry_ms`, then HARD-delete tombstones older than 14 days.
    ///
    /// Two stages on purpose. A package that merely expired may still be named by a Welcome already
    /// in flight, so it becomes a tombstone and stays explainable; only once nothing could still
    /// reasonably refer to it does the row itself go.
    pub fn delete_expired(&mut self, expired_ids: &[Vec<u8>], now_trusted_ms: u64)
            -> Result<usize, StorageErr> {
        const PURGE_AFTER_MS: u64 = 14 * 24 * 60 * 60 * 1000;
        for id in expired_ids {
            self.delete(id)?;
        }
        let mut purged = 0usize;
        if let Ok(rd) = fs::read_dir(&self.dir) {
            for ent in rd.flatten() {
                let name = ent.file_name().to_string_lossy().to_string();
                if !name.starts_with("kp_") || !name.ends_with(".dead") { continue; }
                let died = fs::read_to_string(ent.path()).ok()
                    .and_then(|raw| Self::tombstone_trusted_ms(&raw));
                // A tombstone we cannot read is purged on age-unknown rather than kept forever:
                // it carries no material, and an unreadable stamp cannot be compared.
                let stale = match died {
                    Some(t) => now_trusted_ms.saturating_sub(t) >= PURGE_AFTER_MS,
                    None => true,
                };
                if stale && fs::remove_file(ent.path()).is_ok() { purged += 1; }
            }
        }
        Ok(purged)
    }

}

impl KeyPackageStorage for FileKeyPackageStorage {
    type Error = StorageErr;

    fn insert(&mut self, id: Vec<u8>, pkg: KeyPackageData) -> Result<(), StorageErr> {
        fs::create_dir_all(&self.dir)?;
        let data = pkg.mls_encode_to_vec().map_err(|e| StorageErr(format!("kp encode: {e:?}")))?;
        self.write_atomic(&self.kp_path(&id), &data)
    }


    /// TOMBSTONE, not a hard delete.
    ///
    /// The private material goes; the fact that this package existed and was consumed stays. That is
    /// the whole value: a Welcome referring to a package we already used is a REAL and common event
    /// (a peer that fetched our KP twice, a retried add), and against a hard delete it is
    /// indistinguishable from a package we never had — a bare `None` with nothing to say about it.
    /// With a tombstone the same Welcome gets `Welcome key package not found` and a reason.
    ///
    /// Guarded on not-already-deleted: re-tombstoning would reset the 14-day purge clock and keep a
    /// dead row alive indefinitely.
    fn delete(&mut self, id: &[u8]) -> Result<(), StorageErr> {
        if crate::ffi::writes_forbidden() {
            return Err(StorageErr("kp delete refused inside a READ-ONLY scope (item 1.7)".into()));
        }
        let p = self.kp_path(id);
        if p.exists() { fs::remove_file(&p)?; }
        let t = self.kp_tombstone_path(id);
        if t.exists() {
            // Already dead. Leaving the original stamp alone is what makes the purge window mean
            // "14 days since it died" rather than "14 days since someone last asked".
            return Ok(());
        }
        let now = now_ms();
        self.write_atomic(&t, Self::tombstone_payload(now, now).as_bytes())
    }

    fn get(&self, id: &[u8]) -> Result<Option<KeyPackageData>, StorageErr> {
        let p = self.kp_path(id);
        if !p.exists() {
            // A TOMBSTONED package still returns NotFound — the material really is gone — but it
            // LOGS, and that is the difference the item exists for. mls-rs will surface this as a
            // Welcome failure either way; without the line, "a peer used a KP we consumed" and "a
            // peer used a KP we never had" are the same bare miss with the same empty explanation.
            if self.kp_tombstone_path(id).exists() {
                crate::ffi::alog!(
                    "Welcome key package not found: kp {} was CONSUMED (WELCOME_KEY_PACKAGE_NOT_FOUND)",
                    Self::hexid(id));
            }
            return Ok(None);
        }
        let raw = fs::read(&p)?;
        let pkg = KeyPackageData::mls_decode(&mut &raw[..])
            .map_err(|e| StorageErr(format!("kp decode: {e:?}")))?;
        Ok(Some(pkg))
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    fn tmpdir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("rcsstore_{}_{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }
    fn gs(id: &[u8], data: &[u8]) -> GroupState {
        GroupState { id: id.to_vec(), data: data.to_vec().into() }
    }
    fn ep(id: u64, data: &[u8]) -> EpochRecord {
        EpochRecord { id, data: data.to_vec().into() }
    }

    /// The state and its epoch records must land TOGETHER. Written as one rename, so there is no
    /// interleaving to observe — this pins the round-trip that replaced the multi-rename version.
    #[test]
    fn state_and_epochs_round_trip_as_one_unit() {
        let d = tmpdir("unit");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"g1".to_vec();
        st.write(gs(&gid, b"STATE-1"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();

        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"STATE-1".to_vec());
        assert_eq!(st.epoch(&gid, 1).unwrap().unwrap().to_vec(), b"E1".to_vec());
        assert_eq!(st.epoch(&gid, 2).unwrap().unwrap().to_vec(), b"E2".to_vec());
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(2));
        assert_eq!(st.epoch(&gid, 99).unwrap(), None);

        // Exactly ONE file per group — the thing that makes the write atomic.
        let n = fs::read_dir(&d).unwrap().filter(|e| {
            e.as_ref().unwrap().file_name().to_string_lossy().starts_with("g_")
        }).count();
        assert_eq!(n, 1, "one record file per group");
        let _ = fs::remove_dir_all(&d);
    }

    /// An update to an existing epoch must replace it, not duplicate it.
    #[test]
    fn epoch_updates_replace_in_place() {
        let d = tmpdir("upd");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"g1".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(1, b"OLD")], vec![]).unwrap();
        st.write(gs(&gid, b"S2"), vec![], vec![ep(1, b"NEW")]).unwrap();
        assert_eq!(st.epoch(&gid, 1).unwrap().unwrap().to_vec(), b"NEW".to_vec());
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"S2".to_vec());
        let _ = fs::remove_dir_all(&d);
    }

    /// LIVE DEVICES HOLD GROUPS IN THE OLD LAYOUT. Reading must fall back to it, and the next write
    /// must fold it into the combined record and retire the old files — losing nothing either way.
    #[test]
    fn legacy_layout_is_read_then_migrated() {
        let d = tmpdir("legacy");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gL".to_vec();
        // Hand-build the legacy files the previous implementation would have written.
        fs::write(st.state_path(&gid), b"LEGACY-STATE").unwrap();
        fs::write(st.epoch_path(&gid, 7), b"LEGACY-E7").unwrap();
        fs::write(st.epoch_path(&gid, 8), b"LEGACY-E8").unwrap();

        // Read: the group is intact BEFORE any migration write.
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"LEGACY-STATE".to_vec());
        assert_eq!(st.epoch(&gid, 7).unwrap().unwrap().to_vec(), b"LEGACY-E7".to_vec());
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(8));

        // Write: folds them in, retires the legacy files, keeps the old epochs.
        st.write(gs(&gid, b"NEW-STATE"), vec![ep(9, b"E9")], vec![]).unwrap();
        assert!(!st.state_path(&gid).exists(), "legacy state file retired");
        assert!(!st.epoch_path(&gid, 7).exists(), "legacy epoch file retired");
        assert!(st.record_path(&gid).exists());
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"NEW-STATE".to_vec());
        assert_eq!(st.epoch(&gid, 7).unwrap().unwrap().to_vec(), b"LEGACY-E7".to_vec(),
                   "a migrated epoch must survive the migration");
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(9));
        let _ = fs::remove_dir_all(&d);
    }

    const DAY_MS: u64 = 24 * 60 * 60 * 1000;
    const NOW: u64 = 1_757_000_000_000;   // a fixed instant; the age tests never read the clock

    /// The v1 encoder, verbatim — an UNSTAMPED record exactly as a build before this change wrote it.
    /// Kept as a fixture rather than a call into the current encoder on purpose: the point of these
    /// tests is that the format we no longer produce still loads.
    fn v1_record_bytes(state: &[u8], epochs: &[(u64, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(RECORD_MAGIC);
        out.push(RECORD_VERSION_UNSTAMPED);
        out.extend_from_slice(&(state.len() as u32).to_be_bytes());
        out.extend_from_slice(state);
        out.extend_from_slice(&(epochs.len() as u32).to_be_bytes());
        for (id, d) in epochs {
            out.extend_from_slice(&id.to_be_bytes());
            out.extend_from_slice(&(d.len() as u32).to_be_bytes());
            out.extend_from_slice(d);
        }
        out
    }

    fn rec_with(epochs: &[(u64, Option<u64>)]) -> GroupRecord {
        let mut r = GroupRecord { state: Some(b"S".to_vec()), ..Default::default() };
        for (id, ts) in epochs {
            r.epochs.insert(*id, EpochEntry { stored_ms: *ts, data: vec![*id as u8] });
        }
        r
    }

    fn ids(r: &GroupRecord) -> Vec<u64> { r.epochs.keys().copied().collect() }

    /// Rewrite one epoch's stamp on disk — the only way to age an epoch without waiting three days.
    fn restamp_on_disk(st: &FileGroupStateStorage, gid: &[u8], id: u64, stored_ms: Option<u64>) {
        let mut rec = GroupRecord::decode(&fs::read(st.record_path(gid)).unwrap()).unwrap();
        rec.epochs.get_mut(&id).expect("epoch present").stored_ms = stored_ms;
        fs::write(st.record_path(gid), rec.encode()).unwrap();
    }

    fn stamp_on_disk(st: &FileGroupStateStorage, gid: &[u8], id: u64) -> Option<u64> {
        GroupRecord::decode(&fs::read(st.record_path(gid)).unwrap()).unwrap()
            .epochs.get(&id).expect("epoch present").stored_ms
    }

    /// RCC.16 §6.1.1 asks for a DURATION, and a count answers a different question. Past the window
    /// the record goes, however few of them there are.
    #[test]
    fn an_epoch_past_the_age_window_is_dropped() {
        let mut r = rec_with(&[
            (1, Some(NOW - 4 * DAY_MS)),   // older than 3 days
            (2, Some(NOW - 3 * DAY_MS - 1)),
            (3, Some(NOW - 3 * DAY_MS)),   // exactly at the boundary — "at least 3 days" KEEPS it
            (4, Some(NOW)),
        ]);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (2, 0));
        assert_eq!(ids(&r), vec![3, 4], "the boundary epoch is retained, not expired");
    }

    /// The two bounds are independent, and each one alone lets the other's failure through.
    #[test]
    fn age_and_count_bounds_interact() {
        // Written against EPOCH_RETENTION rather than its value: these assert the RULE, and a
        // sizing change (16 -> 64) should not have to re-derive arithmetic to keep them true.
        let cap = EPOCH_RETENTION as u64;
        const OVER: u64 = 14;   // how far past the cap each fixture reaches

        // cap+OVER records, all fresh: the count cap alone does the work.
        let fresh: Vec<(u64, Option<u64>)> = (1..=cap + OVER).map(|i| (i, Some(NOW))).collect();
        let mut r = rec_with(&fresh);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, OVER as usize));
        assert_eq!(ids(&r), (OVER + 1..=cap + OVER).collect::<Vec<_>>(),
                   "count cap keeps the newest EPOCH_RETENTION");

        // Same set with all but ten stale: age takes them and what is left is inside the cap, so
        // the count pass has nothing to do.
        let stale_upto = cap + OVER - 10;
        let mixed: Vec<(u64, Option<u64>)> = (1..=cap + OVER)
            .map(|i| (i, Some(if i <= stale_upto { NOW - 5 * DAY_MS } else { NOW })))
            .collect();
        let mut r = rec_with(&mixed);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (stale_upto as usize, 0));
        assert_eq!(ids(&r), (stale_upto + 1..=cap + OVER).collect::<Vec<_>>());

        // Five stale on top of an over-cap set: BOTH fire, age first. Age-first matters — capping
        // first would have evicted five young epochs to make room for five the age pass then
        // deleted, so the counts here (5 aged, OVER capped) are the assertion, not the survivors.
        let both: Vec<(u64, Option<u64>)> = (1..=cap + OVER + 5)
            .map(|i| (i, Some(if i <= 5 { NOW - 9 * DAY_MS } else { NOW })))
            .collect();
        let mut r = rec_with(&both);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (5, OVER as usize));
        assert_eq!(ids(&r), (OVER + 6..=cap + OVER + 5).collect::<Vec<_>>());
    }

    /// A v1 record holds live epoch secrets on the fleet. It must still LOAD — refusing it, or
    /// treating its undated epochs as infinitely old, destroys decryption material outright.
    #[test]
    fn a_v1_record_still_loads_and_its_epochs_are_age_unknown() {
        let d = tmpdir("v1load");
        let st = FileGroupStateStorage::new(&d);
        let gid = b"gV1".to_vec();
        fs::write(st.record_path(&gid),
            v1_record_bytes(b"V1-STATE", &[(4, b"E4"), (5, b"E5")])).unwrap();

        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"V1-STATE".to_vec());
        assert_eq!(st.epoch(&gid, 4).unwrap().unwrap().to_vec(), b"E4".to_vec());
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(5));

        // Undated, not old: a decade of clock cannot expire an epoch we have no date for.
        let mut rec = GroupRecord::decode(&fs::read(st.record_path(&gid)).unwrap()).unwrap();
        assert!(rec.epochs.values().all(|e| e.stored_ms.is_none()), "v1 epochs are age-UNKNOWN");
        let (aged, capped) = rec.trim_at(NOW + 3650 * DAY_MS, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, 0), "an unknown-age epoch must never be evicted BY AGE");
        let _ = fs::remove_dir_all(&d);
    }

    /// ...but the count cap still applies to it, which is what keeps the undated set self-draining.
    #[test]
    fn an_unknown_age_epoch_still_yields_to_the_count_cap() {
        let n = EPOCH_RETENTION as u64 + 4;
        let mut r = rec_with(&(1..=n).map(|i| (i, None)).collect::<Vec<_>>());
        let (aged, capped) = r.trim_at(NOW + 3650 * DAY_MS, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, 4));
        assert_eq!(ids(&r), (5..=n).collect::<Vec<_>>());

        // Mixed: the stale STAMPED ones go on age, the undated ones stay until the cap wants them.
        let mut r = rec_with(&[
            (1, None), (2, None), (3, Some(NOW - 9 * DAY_MS)), (4, Some(NOW)),
        ]);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (1, 0));
        assert_eq!(ids(&r), vec![1, 2, 4]);
    }

    /// The migration write-back: a loaded v1 record comes back as v2, its own epochs still undated
    /// (nothing on disk could date them) and the newly archived one stamped.
    #[test]
    fn a_migrated_v1_record_is_written_back_as_v2() {
        let d = tmpdir("v1mig");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gV1M".to_vec();
        fs::write(st.record_path(&gid), v1_record_bytes(b"OLD", &[(7, b"E7")])).unwrap();

        st.write(gs(&gid, b"NEW"), vec![ep(8, b"E8")], vec![]).unwrap();

        let raw = fs::read(st.record_path(&gid)).unwrap();
        assert_eq!(raw[4], RECORD_VERSION, "the write upgrades the record in place");
        assert_eq!(st.epoch(&gid, 7).unwrap().unwrap().to_vec(), b"E7".to_vec(),
                   "the migrated epoch survives the upgrade");
        assert_eq!(stamp_on_disk(&st, &gid, 7), None, "and stays age-UNKNOWN — it cannot be dated");
        let stamped = stamp_on_disk(&st, &gid, 8).expect("a new epoch is stamped");
        assert!(stamped > 1_700_000_000_000, "a real wall-clock stamp, not a sentinel");
        let _ = fs::remove_dir_all(&d);
    }

    /// mls-rs INSERTS an epoch record when the group leaves that epoch and UPDATES it afterwards as
    /// the ratchet advances over late messages. The insert is when the epoch became a *previous*
    /// epoch, so it is the insert that starts §6.1.1's clock — an update must not restart it.
    #[test]
    fn an_update_does_not_restamp_the_retention_clock() {
        let d = tmpdir("restamp");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gU".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();

        // Age epoch 1 past the window, then touch it with an update. The write's own prune runs on
        // the REAL clock, so a restamped entry would survive and an honestly-dated one is dropped.
        restamp_on_disk(&st, &gid, 1, Some(now_ms() - 4 * DAY_MS));
        st.write(gs(&gid, b"S2"), vec![], vec![ep(1, b"E1-UPDATED")]).unwrap();

        assert_eq!(st.epoch(&gid, 1).unwrap(), None,
                   "an update restamped the epoch — retention would never expire a busy epoch");
        assert_eq!(st.epoch(&gid, 2).unwrap().unwrap().to_vec(), b"E2".to_vec());
        let _ = fs::remove_dir_all(&d);
    }

    /// The whole point, end to end: an epoch older than the window is gone after the next write.
    #[test]
    fn the_write_path_prunes_by_age() {
        let d = tmpdir("agewrite");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gA".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();
        restamp_on_disk(&st, &gid, 1, Some(now_ms() - 3 * DAY_MS - 60_000));
        st.write(gs(&gid, b"S2"), vec![ep(3, b"E3")], vec![]).unwrap();
        assert_eq!(st.epoch(&gid, 1).unwrap(), None, "past the window");
        assert!(st.epoch(&gid, 2).unwrap().is_some(), "inside the window");
        assert!(st.epoch(&gid, 3).unwrap().is_some());
        let _ = fs::remove_dir_all(&d);
    }

    /// A device clock can move BACKWARDS (a user setting the date, an NTP correction). That must
    /// keep an epoch, never expire one: over-retention is recoverable, a deleted secret is not.
    #[test]
    fn a_backwards_clock_does_not_expire_an_epoch() {
        let mut r = rec_with(&[(1, Some(NOW))]);
        let (aged, capped) = r.trim_at(NOW - 30 * DAY_MS, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, 0));
        assert_eq!(ids(&r), vec![1]);
    }

    /// The v4.0 raise is a version assignment, not a second edit to this file.
    #[test]
    fn the_retention_window_follows_the_announced_rcc16_version() {
        assert_eq!(retention_ms_for_rcc16_version(crate::rcc16::RCC16_V3_0), 3 * DAY_MS);
        assert_eq!(retention_ms_for_rcc16_version(crate::rcc16::RCC16_V4_0), 30 * DAY_MS);
        // A version we have never heard of falls back to v3.0 rather than to the longer window:
        // set_rcc16_version refuses unknown values, so this arm is unreachable through the public
        // API and exists only so the mapping is total.
        assert_eq!(retention_ms_for_rcc16_version(0), 3 * DAY_MS);
    }

    /// The sweep for a group that stopped committing — the only shape that can meet v4.0's
    /// delete-at-30-days ceiling, since nothing else prunes a dormant group.
    #[test]
    fn the_sweep_prunes_a_dormant_group_and_writes_only_when_it_must() {
        let d = tmpdir("sweep");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gW".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();
        restamp_on_disk(&st, &gid, 1, Some(now_ms() - 9 * DAY_MS));

        // Nothing to do: no write, so the record file is byte-identical afterwards.
        let before = fs::read(st.record_path(&gid)).unwrap();
        assert_eq!(st.prune_expired_epochs(&gid, now_ms() - 100 * DAY_MS), 0);
        assert_eq!(fs::read(st.record_path(&gid)).unwrap(), before, "a no-op sweep must not write");

        assert_eq!(st.prune_expired_epochs(&gid, now_ms()), 1);
        assert_eq!(st.epoch(&gid, 1).unwrap(), None);
        assert_eq!(st.epoch(&gid, 2).unwrap().unwrap().to_vec(), b"E2".to_vec());
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"S".to_vec(), "state untouched");
        let _ = fs::remove_dir_all(&d);
    }

    /// The whole set is rewritten per write, so it has to be bounded — but bounded from the NEWEST
    /// end: dropping recent epochs would break decryption of in-flight messages.
    #[test]
    fn epoch_retention_keeps_the_newest() {
        let d = tmpdir("trim");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gT".to_vec();
        let n = EPOCH_RETENTION as u64 + 24;
        let inserts: Vec<EpochRecord> = (1..=n).map(|i| ep(i, b"x")).collect();
        st.write(gs(&gid, b"S"), inserts, vec![]).unwrap();
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(n));
        assert!(st.epoch(&gid, n).unwrap().is_some(), "newest kept");
        assert!(st.epoch(&gid, n - EPOCH_RETENTION as u64 + 1).unwrap().is_some(),
                "the oldest epoch still inside the cap");
        assert!(st.epoch(&gid, 1).unwrap().is_none(), "oldest trimmed");
        let _ = fs::remove_dir_all(&d);
    }

    /// A corrupt record must ERROR, never read as an empty group — "no state" means "never joined",
    /// which sends the caller down the create path for a conversation it is already in.
    #[test]
    fn a_corrupt_record_errors_rather_than_reading_empty() {
        let d = tmpdir("corrupt");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gC".to_vec();
        st.write(gs(&gid, b"STATE"), vec![ep(1, b"E1")], vec![]).unwrap();
        let raw = fs::read(st.record_path(&gid)).unwrap();
        fs::write(st.record_path(&gid), &raw[..raw.len() - 3]).unwrap();   // truncate
        assert!(st.state(&gid).is_err(), "truncated record must not read as an empty group");
        fs::write(st.record_path(&gid), b"NOPE0000").unwrap();
        assert!(st.state(&gid).is_err(), "bad magic must not read as an empty group");
        let _ = fs::remove_dir_all(&d);
    }

    /// A snapshot now carries the epochs too, so a rollback can bring an epoch record BACK — which a
    /// state-only snapshot plus a delete-above-high-water restore could never do.
    #[test]
    fn snapshot_restores_epochs_not_just_the_state() {
        let d = tmpdir("snap");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gS".to_vec();
        st.write(gs(&gid, b"BEFORE"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();
        let snap = st.export_state(&gid).unwrap();

        // Advance, then roll back.
        st.write(gs(&gid, b"AFTER"), vec![ep(3, b"E3")], vec![]).unwrap();
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(3));
        st.restore_state(&gid, &snap, 2);
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"BEFORE".to_vec());
        assert_eq!(st.epoch(&gid, 2).unwrap().unwrap().to_vec(), b"E2".to_vec());
        assert_eq!(st.epoch(&gid, 3).unwrap(), None, "the un-acked epoch is gone");
        let _ = fs::remove_dir_all(&d);
    }

    /// A snapshot taken by an OLDER build is a bare state blob with no magic. It must still restore.
    #[test]
    fn a_legacy_snapshot_still_restores() {
        let d = tmpdir("snaplegacy");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gS2".to_vec();
        st.write(gs(&gid, b"CUR"), vec![ep(1, b"E1"), ep(5, b"E5")], vec![]).unwrap();
        st.restore_state(&gid, b"OLD-BARE-BLOB", 1);
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"OLD-BARE-BLOB".to_vec());
        assert_eq!(st.epoch(&gid, 1).unwrap().unwrap().to_vec(), b"E1".to_vec());
        assert_eq!(st.epoch(&gid, 5).unwrap(), None, "epochs above the high-water mark dropped");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn delete_group_removes_both_layouts() {
        let d = tmpdir("del");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gD".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(1, b"E")], vec![]).unwrap();
        fs::write(st.state_path(&gid), b"stale-legacy").unwrap();
        st.delete_group(&gid);
        assert!(!st.record_path(&gid).exists());
        assert!(!st.state_path(&gid).exists());
        assert_eq!(st.state(&gid).unwrap(), None);
        let _ = fs::remove_dir_all(&d);
    }
}

#[cfg(test)]
mod invariant_tests {
    use super::*;

    fn tmpdir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("rcsinv_{}_{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }
    fn gs(id: &[u8], data: &[u8]) -> GroupState {
        GroupState { id: id.to_vec(), data: data.to_vec().into() }
    }
    fn ep(id: u64, data: &[u8]) -> EpochRecord {
        EpochRecord { id, data: data.to_vec().into() }
    }

    /// An UPDATE matching zero rows aborts the WHOLE write.
    #[test]
    fn update_matching_zero_rows_aborts_the_whole_write() {
        let d = tmpdir("inv68");
        let mut st = FileGroupStateStorage::new(&d);
        st.write(gs(b"g", b"state-1"), vec![ep(1, b"e1")], vec![]).unwrap();

        // An update for epoch 9, which was never inserted.
        let r = st.write(gs(b"g", b"state-2"), vec![ep(2, b"e2")], vec![ep(9, b"nope")]);
        assert!(r.is_err(), "an update matching zero rows must not succeed");

        // ...and NOTHING from that batch landed. This is the half that matters: a store that
        // aborted after applying the state, or after the inserts, would leave the group in a state
        // that never existed — which is worse than either completing or refusing.
        assert_eq!(st.state(b"g").unwrap().unwrap().to_vec(), b"state-1".to_vec());
        assert!(st.epoch(b"g", 2).unwrap().is_none(), "the insert must have been rolled back too");
        assert_eq!(st.epoch(b"g", 1).unwrap().unwrap().to_vec(), b"e1".to_vec());
    }

    /// An update for a record that DOES exist is ordinary, and still one atomic unit with the rest.
    #[test]
    fn update_matching_a_row_succeeds() {
        let d = tmpdir("inv68ok");
        let mut st = FileGroupStateStorage::new(&d);
        st.write(gs(b"g", b"s1"), vec![ep(1, b"old")], vec![]).unwrap();
        st.write(gs(b"g", b"s2"), vec![ep(2, b"new")], vec![ep(1, b"updated")]).unwrap();
        assert_eq!(st.state(b"g").unwrap().unwrap().to_vec(), b"s2".to_vec());
        assert_eq!(st.epoch(b"g", 1).unwrap().unwrap().to_vec(), b"updated".to_vec());
        assert_eq!(st.epoch(b"g", 2).unwrap().unwrap().to_vec(), b"new".to_vec());
    }

    /// Constructing a store must not touch the filesystem, because the constructors are
    /// called from read-shaped verbs.
    #[test]
    fn constructing_a_store_creates_no_directory() {
        let base = tmpdir("noside");
        let never = base.join("should-not-exist");
        let _gs = FileGroupStateStorage::new(&never);
        let _kp = FileKeyPackageStorage::new(&never);
        assert!(!never.exists(), "the constructor wrote to the filesystem from a read path");
    }

    /// The storage layer refuses writes inside a read-only scope.
    #[test]
    fn writes_are_refused_inside_a_read_only_scope() {
        let d = tmpdir("ro");
        let mut st = FileGroupStateStorage::new(&d);
        st.write(gs(b"g", b"before"), vec![], vec![]).unwrap();

        let refused = crate::ffi::read_only(|| {
            st.write(gs(b"g", b"during"), vec![], vec![])
        });
        assert!(refused.is_err(), "a write inside a read-only scope must be refused");
        assert_eq!(st.state(b"g").unwrap().unwrap().to_vec(), b"before".to_vec());

        // ...and the scope closes, so the next write is ordinary again.
        st.write(gs(b"g", b"after"), vec![], vec![]).unwrap();
        assert_eq!(st.state(b"g").unwrap().unwrap().to_vec(), b"after".to_vec());
    }

    /// Depth, not a flag: a nested scope must not re-enable writes when the inner one closes.
    #[test]
    fn read_only_scopes_nest() {
        let d = tmpdir("ronest");
        let mut st = FileGroupStateStorage::new(&d);
        let r = crate::ffi::read_only(|| {
            let _inner = crate::ffi::read_only(|| ());
            st.write(gs(b"g", b"x"), vec![], vec![])
        });
        assert!(r.is_err(), "the outer scope must still forbid writes");
        assert!(!crate::ffi::writes_forbidden(), "the scope must close on the way out");
    }

    /// A consumed key package must leave a TOMBSTONE, not vanish.
    #[test]
    fn a_consumed_key_package_is_tombstoned_not_erased() {
        use mls_rs_core::key_package::KeyPackageStorage as _;
        let dir = format!("{}/kptomb_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let mut st = FileKeyPackageStorage::new(&dir);
        let id = vec![0xAAu8, 0xBB];

        // Deleting a package we never had still records that it is gone — a Welcome naming it gets
        // the same explanation either way, which is the point.
        st.delete(&id).unwrap();
        assert!(st.kp_tombstone_path(&id).exists(), "delete must leave a tombstone");
        assert!(st.get(&id).unwrap().is_none(), "the material really is gone");

        // Re-deleting must NOT restamp: the purge window means "14 days since it died", not
        // "14 days since someone last asked".
        let first = std::fs::read_to_string(st.kp_tombstone_path(&id)).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(5));
        st.delete(&id).unwrap();
        assert_eq!(first, std::fs::read_to_string(st.kp_tombstone_path(&id)).unwrap(),
            "re-tombstoning must not reset the purge clock");

        // Both clocks are recorded; only the TRUSTED one is ever compared.
        assert_eq!(first.split(',').count(), 2, "two clock columns");
        let trusted = FileKeyPackageStorage::tombstone_trusted_ms(&first).unwrap();
        assert!(trusted > 0);

        // A tombstone younger than 14 days SURVIVES a purge — a Welcome in flight may still name it.
        let purged = st.delete_expired(&[], trusted + 1000).unwrap();
        assert_eq!(purged, 0, "a fresh tombstone must not be purged");
        assert!(st.kp_tombstone_path(&id).exists());

        // Past the window it goes.
        let fortnight = 14 * 24 * 60 * 60 * 1000u64;
        let purged = st.delete_expired(&[], trusted + fortnight).unwrap();
        assert_eq!(purged, 1, "a tombstone past 14 days is hard-deleted");
        assert!(!st.kp_tombstone_path(&id).exists());
    }
}
