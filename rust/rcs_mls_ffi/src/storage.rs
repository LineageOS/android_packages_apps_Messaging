//
// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

//! File-backed mls-rs storage: group state with its prior epochs, and KeyPackage secrets, under a
//! per-identity directory. See docs/mls/rust-core.md.
use std::fs;
use std::path::PathBuf;
use zeroize::Zeroizing;
use mls_rs_core::group::{EpochRecord, GroupState, GroupStateStorage};
use mls_rs_core::key_package::{KeyPackageData, KeyPackageStorage};
use mls_rs_core::error::IntoAnyError;
use mls_rs::mls_rs_codec::{MlsEncode, MlsDecode};

#[derive(Debug)]
// The message is read only through Debug, which dead-code analysis does not count.
pub struct StorageErr(#[allow(dead_code)] pub String);
impl IntoAnyError for StorageErr {}
impl From<std::io::Error> for StorageErr {
    fn from(e: std::io::Error) -> Self { StorageErr(e.to_string()) }
}

// Each group is one record in one file written by one rename, because `GroupStateStorage::write`
// must publish the state and the epoch records it references together; mls-rs cannot recover a
// state whose epoch records are missing. The KeyPackage store needs no transaction: mls-rs writes
// the group state before deleting the consumed KeyPackage.

/// File magic of the combined record; also tags an exported snapshot.
const RECORD_MAGIC: &[u8; 4] = b"RCSG";

/// Bump only for a change the decoder cannot absorb. Version 2 adds the per-epoch stamp; version 1
/// is still read (its epochs have unknown age) and is rewritten as version 2 on the next write.
const RECORD_VERSION: u8 = 2;

/// Read only.
const RECORD_VERSION_UNSTAMPED: u8 = 1;

/// Count bound on retained prior epochs per group. The age bound ([`epoch_retention_ms`]) decides
/// in ordinary use; this caps the record, which is rewritten on every message processed or
/// encrypted, while still covering three days of a busy group and a peer lagging tens of epochs
/// behind. An era change clears the archive outright (`create_group_carry`,
/// `purge_prior_epochs_on_join`). Raising it for v4.0 first needs the archive off the per-message
/// write path. See docs/mls/rust-core.md.
const EPOCH_RETENTION: usize = 64;

/// RCC.16 v3.0 §6.1.1: keep previous-epoch secrets for at least 3 days.
const EPOCH_RETENTION_MS_V3_0: u64 = 3 * 24 * 60 * 60 * 1000;

/// RCC.16 v4.0 §6.1.1: 30 days, then delete; the ceiling needs
/// [`FileGroupStateStorage::prune_expired_epochs`] as well.
const EPOCH_RETENTION_MS_V4_0: u64 = 30 * 24 * 60 * 60 * 1000;

/// The age window for the announced RCC.16 revision. Every prune goes through here.
fn epoch_retention_ms() -> u64 {
    retention_ms_for_rcc16_version(crate::rcc16::rcc16_version())
}

/// Separate so the mapping is testable without touching the process-wide version.
fn retention_ms_for_rcc16_version(v: u8) -> u64 {
    if v >= crate::rcc16::RCC16_V4_0 { EPOCH_RETENTION_MS_V4_0 } else { EPOCH_RETENTION_MS_V3_0 }
}

/// Device wall clock in ms since the UNIX epoch, 0 before 1970. A backward jump retains longer;
/// a forward jump could expire early, which is why the age comparison saturates and the count cap
/// stays.
fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// One retained prior-epoch record and when it was stored.
#[derive(Clone)]
struct EpochEntry {
    /// Wall-clock ms at which the record was first stored; `None` when unknown (v1 or legacy
    /// layout). Unknown is not old: such entries are evicted only by the count cap, never by age.
    stored_ms: Option<u64>,
    data: Vec<u8>,
}

/// One group's persisted state and its retained epoch records.
#[derive(Default)]
struct GroupRecord {
    state: Option<Vec<u8>>,
    /// Ordered, so trimming keeps the newest epochs and the max id is the last key.
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
            // 0 encodes an unknown stamp.
            out.extend_from_slice(&e.stored_ms.unwrap_or(0).to_be_bytes());
            out.extend_from_slice(&(e.data.len() as u32).to_be_bytes());
            out.extend_from_slice(&e.data);
        }
        out
    }

    /// Strict: a truncated or unknown record is an error, never an empty group, which would read
    /// as "never joined".
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
            // v1 has no stamp field; its epochs have unknown age.
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

    /// Drops epoch records older than `retention_ms`, then any over [`EPOCH_RETENTION`] (keeping
    /// the newest). Age first, so the cap never evicts a young epoch to keep an expiring one.
    /// Unknown-age entries are skipped by the age pass. Returns `(aged_out, capped_out)`.
    ///
    /// The age pass never lowers the highest id: mls-rs archives epoch N only when the stored max
    /// is N-1, so a kept older record below a dropped top one fails every later commit on the group
    /// with `InvalidEpoch`. When the top record ages out, the rest goes with it.
    fn trim_at(&mut self, now_ms: u64, retention_ms: u64) -> (usize, usize) {
        let start = self.epochs.len();
        let top = self.epochs.keys().next_back().copied();
        self.epochs.retain(|_, e| match e.stored_ms {
            // A clock that moved backwards gives age 0 and keeps the entry.
            Some(t) => now_ms.saturating_sub(t) <= retention_ms,
            None => true,
        });
        // An undated record, or one stamped before a backwards clock jump, can outlive the top.
        if top.is_some_and(|t| !self.epochs.contains_key(&t)) {
            self.epochs.clear();
        }
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
    /// Touches no files; the directory is created by the write paths, since read verbs construct
    /// stores too.
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }
    fn hexid(gid: &[u8]) -> String {
        gid.iter().map(|b| format!("{b:02x}")).collect()
    }
    fn record_path(&self, gid: &[u8]) -> PathBuf {
        self.dir.join(format!("g_{}.bin", Self::hexid(gid)))
    }
    /// Legacy per-file layout, read only: loads fall back to it and the next write folds it into
    /// the combined record.
    fn state_path(&self, gid: &[u8]) -> PathBuf {
        self.dir.join(format!("s_{}.bin", Self::hexid(gid)))
    }
    #[cfg(test)]
    fn epoch_path(&self, gid: &[u8], eid: u64) -> PathBuf {
        self.dir.join(format!("e_{}_{:020}.bin", Self::hexid(gid), eid))
    }
    /// Temp file and rename.
    fn write_atomic(&self, path: &PathBuf, data: &[u8]) -> Result<(), StorageErr> {
        // Every write passes through here, so the read-only scope is enforced at one place.
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

    /// Loads a group's record, falling back to the legacy layout.
    fn load(&self, gid: &[u8]) -> Result<GroupRecord, StorageErr> {
        let p = self.record_path(gid);
        if p.exists() {
            return GroupRecord::decode(&fs::read(&p)?);
        }
        // Legacy: s_<gid>.bin and e_<gid>_<epoch>.bin
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
                                // unknown age, as for a v1 record
                                rec.epochs.insert(id, EpochEntry { stored_ms: None, data });
                            }
                        }
                    }
                }
            }
        }
        Ok(rec)
    }

    /// Writes the record atomically, then removes legacy files for the group; the cleanup follows
    /// the durable write so an interrupted migration loses nothing.
    fn store(&self, gid: &[u8], rec: &mut GroupRecord) -> Result<(), StorageErr> {
        fs::create_dir_all(&self.dir)?;
        let (aged, _capped) = rec.trim_at(now_ms(), epoch_retention_ms());
        if aged > 0 {
            // Only age drops are logged: a capped drop is routine, an age drop explains a late
            // message that no longer decrypts.
            crate::ffi::alog!(
                "storage: dropped {aged} prior-epoch record(s) older than {} h (RCC.16 §6.1.1 \
                 retention window, v{} announced)",
                epoch_retention_ms() / 3_600_000, crate::rcc16::rcc16_version());
        }
        let blob = rec.encode();
        // Reported to the host as a state-size metric.
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

    /// Snapshot of the whole record (state and epochs, tagged with the magic) for rollback of an
    /// optimistic commit; `None` if the group is not persisted. See [`Self::restore_state`].
    pub fn export_state(&self, gid: &[u8]) -> Option<Vec<u8>> {
        let rec = self.load(gid).ok()?;
        if rec.state.is_none() && rec.epochs.is_empty() { return None; }
        Some(rec.encode())
    }

    /// Highest stored epoch id, 0 if none, without importing the `GroupStateStorage` trait.
    pub fn current_max_epoch(&self, gid: &[u8]) -> u64 {
        self.load(gid).ok()
            .and_then(|r| r.epochs.keys().next_back().copied())
            .unwrap_or(0)
    }

    /// Restores a snapshot from [`Self::export_state`], or a bare state blob, then drops epoch
    /// records above `keep_max_epoch`. Best-effort.
    pub fn restore_state(&self, gid: &[u8], state: &[u8], keep_max_epoch: u64) {
        let mut rec = if state.len() >= 4 && &state[0..4] == RECORD_MAGIC {
            match GroupRecord::decode(state) {
                Ok(r) => r,
                Err(_) => return,   // never half-restore
            }
        } else {
            // A bare state blob without the magic: replace the state only.
            let mut r = self.load(gid).unwrap_or_default();
            r.state = Some(state.to_vec());
            r
        };
        rec.epochs.retain(|id, _| *id <= keep_max_epoch);
        let _ = self.store(gid, &mut rec);
    }

    /// Deletes the record and any legacy files for the group. Best-effort. Used before a rejoin
    /// by external commit, which must not find stale state.
    pub fn delete_group(&self, gid: &[u8]) {
        let _ = fs::remove_file(self.record_path(gid));
        self.remove_legacy(gid);
    }

    /// Applies the age bound to `gid` without an engine write; writes only if something was
    /// dropped and returns the count. For the RCC.16 v4.0 deletion ceiling, which a dormant group
    /// otherwise never reaches; not called while v3.0 is the default, so only the tests build it.
    #[cfg(test)]
    pub fn prune_expired_epochs(&self, gid: &[u8], now_ms: u64) -> usize {
        let mut rec = match self.load(gid) { Ok(r) => r, Err(_) => return 0 };
        let (aged, _capped) = rec.trim_at(now_ms, epoch_retention_ms());
        if aged == 0 { return 0; }   // a no-op sweep must not write
        if self.store(gid, &mut rec).is_err() { return 0; }
        aged
    }

    /// Makes the archive of `gid` one mls-rs will extend at `epoch`, the group's current epoch:
    /// drops records at or above it, then the rest unless the highest is `epoch - 1`. Returns the
    /// count dropped; writes only when that is not 0. A record broken by an earlier trim (a kept
    /// record below a dropped top one) otherwise fails every commit on the group, ours and the
    /// peers', with `InvalidEpoch`. The dropped secrets only decrypt late messages from those
    /// epochs; the group state is not touched.
    pub fn heal_contiguity(&self, gid: &[u8], epoch: u64) -> usize {
        let mut rec = match self.load(gid) { Ok(r) => r, Err(_) => return 0 };
        let n = rec.epochs.len();
        rec.epochs.retain(|id, _| *id < epoch);
        if rec.epochs.keys().next_back().is_some_and(|m| m + 1 != epoch) {
            rec.epochs.clear();
        }
        let dropped = n - rec.epochs.len();
        if dropped == 0 { return 0; }
        if self.store(gid, &mut rec).is_err() {
            crate::ffi::alog!("storage: heal_contiguity FAILED to write — the discontinuous \
                prior-epoch archive is still there and the next commit will be refused \
                InvalidEpoch");
            return 0;
        }
        dropped
    }

    /// Drops every archived prior epoch for `gid`, keeping the state; returns the count. Called on
    /// every join. mls-rs refuses to archive a non-contiguous epoch id, so a member re-added within
    /// one era (same group id) would otherwise fail its next commit with `InvalidEpoch`; with an
    /// empty archive the check is skipped. See docs/mls/rust-core.md.
    pub fn purge_epochs(&self, gid: &[u8]) -> usize {
        let mut rec = match self.load(gid) { Ok(r) => r, Err(_) => return 0 };
        let n = rec.epochs.len();
        if n == 0 { return 0; }
        rec.epochs.clear();
        // A failed write leaves the old archive in place; report 0 rather than a purge that did
        // not happen.
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

    /// One unit: applies the state and every epoch insert and update to the current record and
    /// commits it with a single rename.
    fn write(
        &mut self,
        state: GroupState,
        epoch_inserts: Vec<EpochRecord>,
        epoch_updates: Vec<EpochRecord>,
    ) -> Result<(), StorageErr> {
        let mut rec = self.load(&state.id)?;
        // An update that matches no stored epoch aborts the whole write, before anything is
        // applied: it means the engine's view of storage has diverged.
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
            // An update never restamps: the insert is when the epoch became a previous epoch, which
            // is what RCC.16 §6.1.1 measures. Every id here was checked above.
            if let Some(slot) = rec.epochs.get_mut(&e.id) { slot.data = e.data.to_vec(); }
        }
        self.store(&state.id, &mut rec)
    }

    fn max_epoch_id(&self, gid: &[u8]) -> Result<Option<u64>, StorageErr> {
        Ok(self.load(gid)?.epochs.keys().next_back().copied())
    }
}

/// File-backed KeyPackageStorage for the private half of each KeyPackage we publish, as
/// `kp_<hex ref>.bin`, so a later session can open a Welcome sealed to it. mls-rs deletes an entry
/// when a join consumes it.
#[derive(Clone)]
pub struct FileKeyPackageStorage { dir: PathBuf }

impl FileKeyPackageStorage {
    /// Touches no files; see [`FileGroupStateStorage::new`].
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }
    fn hexid(id: &[u8]) -> String {
        id.iter().map(|b| format!("{b:02x}")).collect()
    }
    fn kp_path(&self, id: &[u8]) -> PathBuf {
        self.dir.join(format!("kp_{}.bin", Self::hexid(id)))
    }
    /// Tombstone of a deleted package: a sibling file, so the fact of removal outlives the secret.
    fn kp_tombstone_path(&self, id: &[u8]) -> PathBuf {
        self.dir.join(format!("kp_{}.dead", Self::hexid(id)))
    }
    /// `<trusted_ms>,<device_ms>`. Only the trusted clock is compared; the device clock is for
    /// diagnosis.
    fn tombstone_payload(trusted_ms: u64, device_ms: u64) -> String {
        format!("{trusted_ms},{device_ms}")
    }
    #[cfg(test)]
    fn tombstone_trusted_ms(raw: &str) -> Option<u64> {
        raw.split(',').next().and_then(|s| s.trim().parse::<u64>().ok())
    }
    fn write_atomic(&self, path: &PathBuf, data: &[u8]) -> Result<(), StorageErr> {
        // Every write passes through here, so the read-only scope is enforced at one place.
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

    /// Tombstones the expired packages, then hard-deletes tombstones older than 14 days by the
    /// trusted clock; an expired package may still be named by a Welcome in flight. No caller
    /// outside the tests yet.
    #[cfg(test)]
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
                // An unreadable tombstone carries no material and is purged.
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


    /// Tombstones rather than hard-deletes, so a Welcome naming a consumed package can be told
    /// apart from one naming a package we never had. An existing tombstone keeps its stamp, so the
    /// 14-day purge counts from the first deletion.
    fn delete(&mut self, id: &[u8]) -> Result<(), StorageErr> {
        if crate::ffi::writes_forbidden() {
            return Err(StorageErr("kp delete refused inside a READ-ONLY scope (item 1.7)".into()));
        }
        let p = self.kp_path(id);
        if p.exists() { fs::remove_file(&p)?; }
        let t = self.kp_tombstone_path(id);
        if t.exists() {
            return Ok(());
        }
        let now = now_ms();
        self.write_atomic(&t, Self::tombstone_payload(now, now).as_bytes())
    }

    fn get(&self, id: &[u8]) -> Result<Option<KeyPackageData>, StorageErr> {
        let p = self.kp_path(id);
        if !p.exists() {
            // Still not found, but logged as consumed.
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

    /// State and epoch records round-trip as one unit.
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

        // exactly one file per group
        let n = fs::read_dir(&d).unwrap().filter(|e| {
            e.as_ref().unwrap().file_name().to_string_lossy().starts_with("g_")
        }).count();
        assert_eq!(n, 1, "one record file per group");
        let _ = fs::remove_dir_all(&d);
    }

    /// An update replaces an existing epoch rather than duplicating it.
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

    /// The legacy layout loads, and the next write folds it into the combined record.
    #[test]
    fn legacy_layout_is_read_then_migrated() {
        let d = tmpdir("legacy");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gL".to_vec();
        fs::write(st.state_path(&gid), b"LEGACY-STATE").unwrap();
        fs::write(st.epoch_path(&gid, 7), b"LEGACY-E7").unwrap();
        fs::write(st.epoch_path(&gid, 8), b"LEGACY-E8").unwrap();

        // intact before any write
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"LEGACY-STATE".to_vec());
        assert_eq!(st.epoch(&gid, 7).unwrap().unwrap().to_vec(), b"LEGACY-E7".to_vec());
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(8));

        // the write folds them in and removes the legacy files
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
    const NOW: u64 = 1_757_000_000_000;   // fixed instant; the age tests never read the clock

    /// The v1 encoder, kept verbatim as a fixture so the tests prove the old format still loads.
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

    /// Rewrites one epoch's stamp on disk, to age it.
    fn restamp_on_disk(st: &FileGroupStateStorage, gid: &[u8], id: u64, stored_ms: Option<u64>) {
        let mut rec = GroupRecord::decode(&fs::read(st.record_path(gid)).unwrap()).unwrap();
        rec.epochs.get_mut(&id).expect("epoch present").stored_ms = stored_ms;
        fs::write(st.record_path(gid), rec.encode()).unwrap();
    }

    fn stamp_on_disk(st: &FileGroupStateStorage, gid: &[u8], id: u64) -> Option<u64> {
        GroupRecord::decode(&fs::read(st.record_path(gid)).unwrap()).unwrap()
            .epochs.get(&id).expect("epoch present").stored_ms
    }

    /// RCC.16 §6.1.1 is a duration: past the window a record goes, however few there are.
    #[test]
    fn an_epoch_past_the_age_window_is_dropped() {
        let mut r = rec_with(&[
            (1, Some(NOW - 4 * DAY_MS)),   // older than 3 days
            (2, Some(NOW - 3 * DAY_MS - 1)),
            (3, Some(NOW - 3 * DAY_MS)),   // exactly at the boundary: kept
            (4, Some(NOW)),
        ]);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (2, 0));
        assert_eq!(ids(&r), vec![3, 4], "the boundary epoch is retained, not expired");
    }

    /// The two bounds are independent.
    #[test]
    fn age_and_count_bounds_interact() {
        // Written against EPOCH_RETENTION so a sizing change does not break the rule tests.
        let cap = EPOCH_RETENTION as u64;
        const OVER: u64 = 14;   // how far past the cap each fixture reaches

        // All fresh: only the count cap acts.
        let fresh: Vec<(u64, Option<u64>)> = (1..=cap + OVER).map(|i| (i, Some(NOW))).collect();
        let mut r = rec_with(&fresh);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, OVER as usize));
        assert_eq!(ids(&r), (OVER + 1..=cap + OVER).collect::<Vec<_>>(),
                   "count cap keeps the newest EPOCH_RETENTION");

        // All but ten stale: age takes them and the rest fits under the cap.
        let stale_upto = cap + OVER - 10;
        let mixed: Vec<(u64, Option<u64>)> = (1..=cap + OVER)
            .map(|i| (i, Some(if i <= stale_upto { NOW - 5 * DAY_MS } else { NOW })))
            .collect();
        let mut r = rec_with(&mixed);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (stale_upto as usize, 0));
        assert_eq!(ids(&r), (stale_upto + 1..=cap + OVER).collect::<Vec<_>>());

        // Five stale on an over-cap set: both fire, age first (5 aged, OVER capped).
        let both: Vec<(u64, Option<u64>)> = (1..=cap + OVER + 5)
            .map(|i| (i, Some(if i <= 5 { NOW - 9 * DAY_MS } else { NOW })))
            .collect();
        let mut r = rec_with(&both);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (5, OVER as usize));
        assert_eq!(ids(&r), (OVER + 6..=cap + OVER + 5).collect::<Vec<_>>());
    }

    /// A v1 record still loads, and its undated epochs are not treated as old.
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

        // undated is not old
        let mut rec = GroupRecord::decode(&fs::read(st.record_path(&gid)).unwrap()).unwrap();
        assert!(rec.epochs.values().all(|e| e.stored_ms.is_none()), "v1 epochs are age-UNKNOWN");
        let (aged, capped) = rec.trim_at(NOW + 3650 * DAY_MS, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, 0), "an unknown-age epoch must never be evicted BY AGE");
        let _ = fs::remove_dir_all(&d);
    }

    /// The count cap still applies to undated epochs.
    #[test]
    fn an_unknown_age_epoch_still_yields_to_the_count_cap() {
        let n = EPOCH_RETENTION as u64 + 4;
        let mut r = rec_with(&(1..=n).map(|i| (i, None)).collect::<Vec<_>>());
        let (aged, capped) = r.trim_at(NOW + 3650 * DAY_MS, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, 4));
        assert_eq!(ids(&r), (5..=n).collect::<Vec<_>>());

        // stale stamped entries go by age; undated ones stay until the cap
        let mut r = rec_with(&[
            (1, None), (2, None), (3, Some(NOW - 9 * DAY_MS)), (4, Some(NOW)),
        ]);
        let (aged, capped) = r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (1, 0));
        assert_eq!(ids(&r), vec![1, 2, 4]);
    }

    /// The age pass never lowers the archive's max: mls-rs archives epoch N only after N-1, so a
    /// kept older record under a dropped top one fails every later commit.
    #[test]
    fn the_age_pass_never_lowers_the_archive_max() {
        // an undated record (v1, or the legacy layout) below an aged stamped one
        let mut r = rec_with(&[(0, None), (1, Some(NOW - 4 * DAY_MS))]);
        assert_eq!(r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0), (2, 0));
        assert!(ids(&r).is_empty(), "the undated record goes with the top it sat under");
        // a clock that jumped backwards between two inserts: the older id has the later stamp
        let mut r = rec_with(&[(4, Some(NOW)), (5, Some(NOW - 4 * DAY_MS))]);
        assert_eq!(r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0), (2, 0));
        assert!(ids(&r).is_empty());
        // a gap below a surviving max is harmless and stays
        let mut r = rec_with(&[(1, None), (2, Some(NOW - 4 * DAY_MS)), (3, Some(NOW))]);
        assert_eq!(r.trim_at(NOW, EPOCH_RETENTION_MS_V3_0), (1, 0));
        assert_eq!(ids(&r), vec![1, 3]);
    }

    /// `heal_contiguity` leaves an archive ending at `epoch - 1` alone, drops records at or above
    /// `epoch`, and clears one whose max is lower; it writes only when it drops something.
    #[test]
    fn heal_contiguity_keeps_only_an_archive_ending_just_below_the_epoch() {
        let d = tmpdir("heal");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gH".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(0, b"E0"), ep(1, b"E1")], vec![]).unwrap();
        let before = fs::metadata(st.record_path(&gid)).unwrap().modified().unwrap();
        assert_eq!(st.heal_contiguity(&gid, 2), 0, "{{0, 1}} at epoch 2 is contiguous");
        assert_eq!(fs::metadata(st.record_path(&gid)).unwrap().modified().unwrap(), before,
            "a no-op heal must not write");
        assert_eq!(st.heal_contiguity(&gid, 1), 1, "a record at the current epoch is dropped");
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(0));
        assert_eq!(st.heal_contiguity(&gid, 2), 1, "{{0}} at epoch 2 has a gap: cleared");
        assert_eq!(st.max_epoch_id(&gid).unwrap(), None);
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"S".to_vec(), "state untouched");
        assert_eq!(st.heal_contiguity(&gid, 2), 0, "an empty archive is always extendable");
        let _ = fs::remove_dir_all(&d);
    }

    /// A loaded v1 record is written back as v2, old epochs undated and the new one stamped.
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

    /// An update does not restart an epoch's RCC.16 §6.1.1 clock.
    #[test]
    fn an_update_does_not_restamp_the_retention_clock() {
        let d = tmpdir("restamp");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gU".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();

        // Age epoch 1 past the window, then update it; the write prunes on the real clock.
        restamp_on_disk(&st, &gid, 1, Some(now_ms() - 4 * DAY_MS));
        st.write(gs(&gid, b"S2"), vec![], vec![ep(1, b"E1-UPDATED")]).unwrap();

        assert_eq!(st.epoch(&gid, 1).unwrap(), None,
                   "an update restamped the epoch — retention would never expire a busy epoch");
        assert_eq!(st.epoch(&gid, 2).unwrap().unwrap().to_vec(), b"E2".to_vec());
        let _ = fs::remove_dir_all(&d);
    }

    /// An epoch older than the window is gone after the next write.
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

    /// A clock that moved backwards keeps an epoch rather than expiring it.
    #[test]
    fn a_backwards_clock_does_not_expire_an_epoch() {
        let mut r = rec_with(&[(1, Some(NOW))]);
        let (aged, capped) = r.trim_at(NOW - 30 * DAY_MS, EPOCH_RETENTION_MS_V3_0);
        assert_eq!((aged, capped), (0, 0));
        assert_eq!(ids(&r), vec![1]);
    }

    /// The v4.0 window follows the announced revision.
    #[test]
    fn the_retention_window_follows_the_announced_rcc16_version() {
        assert_eq!(retention_ms_for_rcc16_version(crate::rcc16::RCC16_V3_0), 3 * DAY_MS);
        assert_eq!(retention_ms_for_rcc16_version(crate::rcc16::RCC16_V4_0), 30 * DAY_MS);
        // Unreachable through set_rcc16_version, which refuses unknown values; keeps the mapping
        // total.
        assert_eq!(retention_ms_for_rcc16_version(0), 3 * DAY_MS);
    }

    /// The sweep for a group that stopped committing.
    #[test]
    fn the_sweep_prunes_a_dormant_group_and_writes_only_when_it_must() {
        let d = tmpdir("sweep");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gW".to_vec();
        st.write(gs(&gid, b"S"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();
        restamp_on_disk(&st, &gid, 1, Some(now_ms() - 9 * DAY_MS));

        // nothing to do: the record file is unchanged
        let before = fs::read(st.record_path(&gid)).unwrap();
        assert_eq!(st.prune_expired_epochs(&gid, now_ms() - 100 * DAY_MS), 0);
        assert_eq!(fs::read(st.record_path(&gid)).unwrap(), before, "a no-op sweep must not write");

        assert_eq!(st.prune_expired_epochs(&gid, now_ms()), 1);
        assert_eq!(st.epoch(&gid, 1).unwrap(), None);
        assert_eq!(st.epoch(&gid, 2).unwrap().unwrap().to_vec(), b"E2".to_vec());
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"S".to_vec(), "state untouched");
        let _ = fs::remove_dir_all(&d);
    }

    /// The count bound keeps the newest epochs.
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

    /// A corrupt record is an error, never an empty group.
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

    /// A snapshot carries the epochs, so a rollback can restore an epoch record.
    #[test]
    fn snapshot_restores_epochs_not_just_the_state() {
        let d = tmpdir("snap");
        let mut st = FileGroupStateStorage::new(&d);
        let gid = b"gS".to_vec();
        st.write(gs(&gid, b"BEFORE"), vec![ep(1, b"E1"), ep(2, b"E2")], vec![]).unwrap();
        let snap = st.export_state(&gid).unwrap();

        st.write(gs(&gid, b"AFTER"), vec![ep(3, b"E3")], vec![]).unwrap();
        assert_eq!(st.max_epoch_id(&gid).unwrap(), Some(3));
        st.restore_state(&gid, &snap, 2);
        assert_eq!(st.state(&gid).unwrap().unwrap().to_vec(), b"BEFORE".to_vec());
        assert_eq!(st.epoch(&gid, 2).unwrap().unwrap().to_vec(), b"E2".to_vec());
        assert_eq!(st.epoch(&gid, 3).unwrap(), None, "the un-acked epoch is gone");
        let _ = fs::remove_dir_all(&d);
    }

    /// A bare state blob without the magic still restores.
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

    /// An update matching no stored epoch aborts the whole write.
    #[test]
    fn update_matching_zero_rows_aborts_the_whole_write() {
        let d = tmpdir("inv68");
        let mut st = FileGroupStateStorage::new(&d);
        st.write(gs(b"g", b"state-1"), vec![ep(1, b"e1")], vec![]).unwrap();

        // epoch 9 was never inserted
        let r = st.write(gs(b"g", b"state-2"), vec![ep(2, b"e2")], vec![ep(9, b"nope")]);
        assert!(r.is_err(), "an update matching zero rows must not succeed");

        // Nothing from the batch landed.
        assert_eq!(st.state(b"g").unwrap().unwrap().to_vec(), b"state-1".to_vec());
        assert!(st.epoch(b"g", 2).unwrap().is_none(), "the insert must have been rolled back too");
        assert_eq!(st.epoch(b"g", 1).unwrap().unwrap().to_vec(), b"e1".to_vec());
    }

    /// An update to an existing record is applied in the same unit.
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

    /// Constructing a store touches no files.
    #[test]
    fn constructing_a_store_creates_no_directory() {
        let base = tmpdir("noside");
        let never = base.join("should-not-exist");
        let _gs = FileGroupStateStorage::new(&never);
        let _kp = FileKeyPackageStorage::new(&never);
        assert!(!never.exists(), "the constructor wrote to the filesystem from a read path");
    }

    /// Writes are refused inside a read-only scope.
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

        st.write(gs(b"g", b"after"), vec![], vec![]).unwrap();
        assert_eq!(st.state(b"g").unwrap().unwrap().to_vec(), b"after".to_vec());
    }

    /// A nested scope keeps writes refused when the inner one closes.
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

    /// A consumed key package leaves a tombstone.
    #[test]
    fn a_consumed_key_package_is_tombstoned_not_erased() {
        use mls_rs_core::key_package::KeyPackageStorage as _;
        let dir = format!("{}/kptomb_{}", std::env::temp_dir().display(), std::process::id());
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let mut st = FileKeyPackageStorage::new(&dir);
        let id = vec![0xAAu8, 0xBB];

        // deleting a package we never had also records it
        st.delete(&id).unwrap();
        assert!(st.kp_tombstone_path(&id).exists(), "delete must leave a tombstone");
        assert!(st.get(&id).unwrap().is_none(), "the material really is gone");

        // re-deleting keeps the original stamp
        let first = std::fs::read_to_string(st.kp_tombstone_path(&id)).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(5));
        st.delete(&id).unwrap();
        assert_eq!(first, std::fs::read_to_string(st.kp_tombstone_path(&id)).unwrap(),
            "re-tombstoning must not reset the purge clock");

        assert_eq!(first.split(',').count(), 2, "two clock columns");
        let trusted = FileKeyPackageStorage::tombstone_trusted_ms(&first).unwrap();
        assert!(trusted > 0);

        // a tombstone younger than 14 days survives a purge
        let purged = st.delete_expired(&[], trusted + 1000).unwrap();
        assert_eq!(purged, 0, "a fresh tombstone must not be purged");
        assert!(st.kp_tombstone_path(&id).exists());

        let fortnight = 14 * 24 * 60 * 60 * 1000u64;
        let purged = st.delete_expired(&[], trusted + fortnight).unwrap();
        assert_eq!(purged, 1, "a tombstone past 14 days is hard-deleted");
        assert!(!st.kp_tombstone_path(&id).exists());
    }
}
