#!/usr/bin/env python3
# Copyright (C) 2026 The LineageOS Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Measure how much of MlsProviderTransport is DECISION and how much is EFFECT.

Kept in the repo rather than in a scratchpad on purpose: a derived number whose generator did
not survive the session is a number nobody can check.

Usage:
    tools/mls/transport-classify.py \
        src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java [--methods]

    --wide-policy   count the 16 further decision-bearing engine classes and the two limiter
                    accessors as POLICY (defect 3 of an earlier audit). NOT the published number; the
                    delta is printed either way so the judgement can be taken with it in hand.

METHOD, so the result can be argued with. A class-level method is classified by which of these its
body reaches:
  NET      a provider RPC (pt(...), ProviderTransport, an mls* binder call)
  PERSIST  SharedPreferences, a *Store, mRecords, the mConv map
  SCHED    WorkManager, MlsRetryWorker, a Handler, MlsOffThread
and whether it is decision-bearing:
  CONST    one of the transport-declared policy constants
  GUARD    MlsPeerGuard / MlsRebuildLimiter / MlsExternalCommitBudget
  POLICY   an engine policy class
  SYSPROP  SystemProperties (an Android dependency, and the reason these cannot be host-tested)

DoD-5 is met when the MIXED bucket (an effect AND a decision in one body) reaches 0, or every
remaining member is in the plan's exemption list naming the source-scan test that covers it.

LIMITS, stated: this is a keyword classifier reading ONE body -- it is not transitive, so a method
whose only effect is inside a private helper is bucketed as though it had none (fetchServerPack's
three callers are the named instances). "Decision, no effect" means "no Android effect other than
logging and sysprops" -- not "pure". Spot-check before quoting a number.

AN EARLIER AUDIT fixed three measured defects in this file on 2026-09-08, with the before/after recorded
in the plan's DoD-5 row rather than silently moved: (1) nine class-level methods were invisible to
the extractor, (2) tags matched inside comments and string literals, (3) POLICY named 15 of 99
engine classes -- left opt-in, see --wide-policy. Re-audit any time with:

    tools/mls/stage0-catalogue.py classifier --rev <commit>
"""
import json
import re
import sys
from collections import Counter

POLICY_CONSTANTS = [
    # Comments in here are safe: the guard blanks them before scanning (see the WIRE_CONSTANTS note
    # below). Both lists are parsed by MlsTransportPolicyConstantGuardTest, and that file's
    # proseInsideEitherListIsNotReadAsAConstant is what keeps this true.
    'REKEY_AFTER_SENDS', 'REKEY_REFUSED_BACKOFF_SENDS', 'UNCONVERGED_BEFORE_REBUILD',
    'PARKS_BEFORE_UNSTICKING_A_HEAL', 'REBUILD_EPISODE_MS', 'MAX_FTD_ATTEMPTS',
    'PEER_FTD_ESCALATE_AT', 'REESTABLISH_COOLDOWN_MS', 'GATE_DEADLINE_MS', 'MAX_GATED_RESENDS',
    'SWEEP_PAGE', 'PENDING_OP_MAX_ATTEMPTS', 'PENDING_OP_MAX_AGE_MS', 'IDENTITY_REFRESH_MS',
    'KP_REPUBLISH_MS', 'POOL_REPAIR_MIN_INTERVAL_MS', 'KP_REPLENISH_AT', 'MAX_PENDING_SUBJECT',
    'RETAINED_CONTENT_KEYS_PER_SLOT',
    # How long a FAILED identity read suppresses the next attempt. POLICY, and the
    # sibling of IDENTITY_REFRESH_MS above rather than a duplicate of it: that one is the weekly
    # window for a SUCCESSFUL refresh, this one bounds a BURST of failures. They have to stay two
    # numbers -- a failed read stamping the weekly pref would let the next call re-write the stamp
    # onIdentityChanged had just cleared to force an immediate re-read, stranding the identity for
    # a week. Seconds, not days, for that reason.
    #
    # DECLARED IN MlsConfig, NOT IN THE TRANSPORT, and that is this guard's doing. It was declared
    # in MlsProviderTransport for one evening and the DoD-1 ratchet caught it -- "1 policy constants
    # declared, up from 0" -- which is exactly the regression the epic's count exists to refuse. It
    # sits beside identityRefreshMs, which is also declared nowhere here for the same reason.
    'IDENTITY_RETRY_BACKOFF_MS',
]
# Wire/ABI, not policy -- correctly transport-local, exempt from DoD-1.
WIRE_CONSTANTS = [
    'ERA_INITIAL', 'ERA_EXT_TYPE', 'ERA_MODE_CREATE', 'ERA_MODE_PRESERVE', 'ERA_MODE_PRESERVE_CTRL',
    'BUILD_FAILED', 'HEAL_LOOK_UNAVAILABLE', 'ERA_ADVANCE_LOOK_UNAVAILABLE',
    # A RETURN SENTINEL, exactly like the two above it and BUILD_FAILED: it separates a
    # measured refusal (the roster we claimed cannot be rebuilt around) from a bare -1 meaning
    # something went wrong, so the caller can tell them apart. It bounds nothing and there is no
    # number to tune -- the threshold the refusal is made against is MlsConfig.kpMinRemainingDays,
    # which lives in the engine and is classified there.
    # THIS NOTE USED TO SAY "no quote characters in this comment". That rule is retired: the guard's
    # reader (MlsSourceScan.withoutPythonComments) now blanks '#' comments before it scans for quoted
    # tokens, so a comment's own prose can no longer be read as a constant name. It was advice where
    # a fix was available, it sat on only ONE of the two lists the same parser reads, and the z0t2
    # agent hit the hazard twice -- once on an apostrophe, once on the double quotes in the
    # replacement comment -- while POLICY_CONSTANTS below carried no warning at all.
    'ERA_ADVANCE_ROSTER_NOT_READY', 'LOCK_ASSERTIONS',
    # The PRE-FILL for contract v60 outcome sink, deliberately outside the AIDL
    # 0..5 value space so that an unwritten sink is distinguishable from OUTCOME_SERVED. Who fixes
    # the value is RcsMlsClaimResult, not us: if the contract ever adds a sixth outcome this must
    # move out of its way. There is nothing to tune -- it is an absence marker, not a threshold.
    'NO_CLAIM_OUTCOME',
    # The resync DRY RUN's two answers, and RETURN SENTINELS in the same sense as
    # ERA_ADVANCE_ROSTER_NOT_READY and BUILD_FAILED above: they separate "we asked and the answer is
    # NO" and "we asked and the answer is YES" from a bare -1 meaning the attempt failed. Reading
    # either as -1 would turn "we did not attempt" into "it does not work", which is the
    # reader-refusal shape this subsystem keeps re-filing.
    #
    # NOT policy, and the distinction is the one DoD-1 is about: there is no number to tune. The
    # decision they report is made against the presence or absence of the GroupInfo extension
    # external_pub (0x0004), which is RFC 9420 §12.4.3.2's requirement and not ours -- no threshold,
    # no window, nothing an operator could raise or lower.
    'RESYNC_DRY_RUN_VIABLE', 'RESYNC_DRY_RUN_NOT_VIABLE',
]

NET = re.compile(r'\bpt\(|\bmProvider\b|ProviderTransport\.|\.mls[A-Z]\w*\(|sendFramed'
                 r'|fetchMissedCommits|claimKeyPackages|publishKeyPackages|createMlsConversation'
                 r'|getMlsGroupInfo|\bsendTo\b|RcsProvider')
PERSIST = re.compile(r'SharedPreferences|getSharedPreferences|\bmRecords\b|Store\.|m\w*Store\b'
                     r'|\.edit\(\)|prefs\(\)|mConv\.|conv\(|convIfAny\(|mSealedCache'
                     r'|mPendingBodies|mRendezvous|mResend')
SCHED = re.compile(r'WorkManager|MlsRetryWorker|postDelayed|Handler|MlsOffThread|scheduleRetry'
                   r'|AlarmManager|\bexecutor\b|Executors|Thread\(')
GUARD = re.compile(r'MlsPeerGuard\.|MlsRebuildLimiter|MlsExternalCommitBudget|allowEraAdvance'
                   r'|allowStateChange|allowJoiningPeer|allowDebugStateChange|allowSelfDeparture'
                   r'|allowedToJoinAll')
POLICY = re.compile(r'MlsRetryPolicy|MlsFetchBudget|MlsAdvancerElection|MlsRecoveryPolicy'
                    r'|MlsSendRetentionPolicy|MlsResendBudget|MlsMonotonicAge|MlsDriveLoop'
                    r'|MlsHealthMachine|MlsHealthPredicates|MlsReestablishPolicy'
                    r'|MlsMaintenancePolicy|MlsUpgradePolicy|MlsContinuityPolicy|MlsDowngradeLadder')
# Measured: (3). POLICY above names 15 of the 99 engine classes. These 16 also return a verdict,
# hold an allowance, or ARE the tunable source production reads -- MlsConfig most of all -- and the
# two limiter accessors are how the transport reaches MlsRebuildLimiter/MlsExternalCommitBudget
# without naming them (resyncViaExternalCommit, the sole charge site of the second, is bucketed
# effect-only for exactly that reason).
#
# OPT-IN, via --wide-policy, and deliberately NOT the default: DoD-5's published baseline is
# asserted against the narrow regex above, and moving the instrument and the target in one step
# would make the claim unfalsifiable. The delta is printed on every run so nobody has to re-derive
# it, and a later stage can take the judgement with the number in hand. This list is also the one
# tools/mls/stage0-catalogue.py's classifier audit (C) reports from -- it imports it from here.
POLICY_WIDE_CLASSES = [
    'MlsConfig', 'MlsEnhancedSelfHeal', 'MlsGroupInfoGate', 'MlsHeaderGate', 'MlsInboundHold',
    'MlsOutboundHold', 'MlsWelcomeAdmission', 'MlsMetadataKeysPolicy', 'MlsPendingQueue',
    'MlsInboundRefusal', 'MlsEraBudgetRecord', 'MlsPeerHealthRecord', 'MlsRebuildWindowRecord',
    'MlsInvariantScan', 'MlsGroupDeliveryLedger', 'MlsParticipantKeyResync',
    # Returns a verdict on two questions -- is a floor-wedged group a candidate for a
    # rebuild, and may the packages we claimed carry one -- so it belongs in this list on the same
    # grounds as MlsGroupInfoGate and MlsWelcomeAdmission. It is HERE and not in the narrow POLICY
    # regex for the reason stated above: the narrow one is what DoD-5's published baseline is
    # asserted against, and moving the instrument and the target together would make the epic
    # unfalsifiable. Under --wide-policy, floorRebuild joins the MIXED bucket, which is the honest
    # reading -- it consults a policy class AND writes ConvState.
    'MlsFloorRebuild',
]
LIMITER_ACCESSORS = r'rebuildLimiter\(\)|xcBudget\(\)'
POLICY_WIDE = re.compile(POLICY.pattern + r'|\b(?:' + '|'.join(POLICY_WIDE_CLASSES) + r')\b|'
                         + LIMITER_ACCESSORS)
CONST = re.compile(r'\b(' + '|'.join(POLICY_CONSTANTS) + r')\b')
SYSPROP = re.compile(r'SystemProperties\.')

SIG = re.compile(
    r'^\s{4}(?:@\w+\s+)*((?:public|private|protected|static|final|synchronized|abstract|native'
    r'|volatile|transient|\s)*)([\w<>\[\],.?\s]+?)\s+(\w+)\s*\(')
NOT_A_METHOD = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'synchronized'}


def strip_comments_and_strings(lines):
    """Blank out comments and string/char literals so brace counting is trustworthy."""
    out, in_block = [], False
    for s in lines:
        res, j, in_str, in_ch = '', 0, False, False
        while j < len(s):
            if in_block:
                if s[j:j + 2] == '*/':
                    in_block = False
                    j += 2
                    continue
                j += 1
                continue
            if in_str:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == '"':
                    in_str = False
                j += 1
                continue
            if in_ch:
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == "'":
                    in_ch = False
                j += 1
                continue
            if s[j:j + 2] == '/*':
                in_block = True
                j += 2
                continue
            if s[j:j + 2] == '//':
                break
            if s[j] == '"':
                in_str = True
                j += 1
                continue
            if s[j] == "'":
                in_ch = True
                j += 1
                continue
            res += s[j]
            j += 1
        out.append(res)
    return out


def methods(lines):
    """Class-level method declarations (4-space indent, outer class body) with their extents.

    BRACE-MATCHES FORWARD FROM THE DECLARATION rather than waiting for the next depth-1 `}`. The
    older loop kept a pending declaration open until it saw a closing brace on a LATER line, so a
    body that opened and closed on its own declaration line never closed it -- and every declaration
    up to the next depth-1 `}` was then skipped. Nine class-level methods were invisible that way,
    two of them (`sendBlockedByGate`, `ensureSession`) load-bearing for DoD-5. Measured: (1).
    """
    code = strip_comments_and_strings(lines)
    found, depth, i, n = [], 0, 0, len(code)
    while i < n:
        line = code[i]
        if depth == 1:
            m = SIG.match(line)
            if m and '=' not in line.split('(')[0] and m.group(3) not in NOT_A_METHOD:
                j, d, opened = i, depth, False
                while j < n:
                    for ch in code[j]:
                        if ch == '{':
                            d += 1
                            opened = True
                        elif ch == '}':
                            d -= 1
                    if opened and d == depth:
                        break
                    if not opened and ';' in code[j]:
                        break       # an abstract/native declaration, not a body
                    j += 1
                if opened:
                    found.append({'name': m.group(3), 'start': i + 1, 'end': j + 1,
                                  'lines': j - i + 1})
                    i = j + 1       # resume after the body; `depth` is unchanged by it
                    continue
        depth += line.count('{') - line.count('}')
        i += 1
    return found


EFFECT_TAGS = ('NET', 'PERSIST', 'SCHED')
DECISION_TAGS = ('CONST', 'GUARD', 'POLICY', 'SYSPROP')


def classify(lines, wide=False):
    """Tag every class-level method. Rows carry `tags`, `name`, `start`, `end`, `lines`.

    THE BODY READ HERE IS COMMENT- AND STRING-STRIPPED. It was not, until an earlier audit (2): the
    brace matcher stripped and the tagger did not, so a {@link #GATE_DEADLINE_MS} in a javadoc or a
    class name inside a log string counted as a reference and three methods sat in the MIXED bucket
    on the strength of prose. Same rule the host guards use (MlsGuardPersistenceTest.codeOnly).
    """
    code = strip_comments_and_strings(lines)
    tags = (('NET', NET), ('PERSIST', PERSIST), ('SCHED', SCHED), ('GUARD', GUARD),
            ('POLICY', POLICY_WIDE if wide else POLICY), ('CONST', CONST), ('SYSPROP', SYSPROP))
    rows = []
    for m in methods(lines):
        body = '\n'.join(code[m['start'] - 1:m['end']])
        rows.append(dict(m, tags=[n for n, rx in tags if rx.search(body)]))
    return rows


def buckets(rows):
    """(mixed, effect-only, decision-only, neither) -- DoD-5 is asserted against the first."""
    effect = lambda r: [t for t in EFFECT_TAGS if t in r['tags']]
    decide = lambda r: [t for t in DECISION_TAGS if t in r['tags']]
    return ([r for r in rows if effect(r) and decide(r)],
            [r for r in rows if effect(r) and not decide(r)],
            [r for r in rows if not effect(r) and decide(r)],
            [r for r in rows if not effect(r) and not decide(r)])


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args:
        print(__doc__)
        return 2
    path = args[0]
    lines = open(path, encoding='utf-8', errors='replace').read().split('\n')
    wide = '--wide-policy' in sys.argv
    rows = classify(lines, wide=wide)
    mixed, eff_only, dec_only, neither = buckets(rows)

    total_lines = sum(r['lines'] for r in rows)
    print('%s' % path)
    print('  file lines            %d' % len(lines))
    print('  class-level methods   %d' % len(rows))
    print('  method-body lines     %d' % total_lines)
    print()
    fmt = '  %-38s %4d methods  %6d lines  %3.0f%%'
    for name, bucket in (('MIXED (effect + decision)  <- DoD-5', mixed),
                         ('effect only', eff_only),
                         ('decision only (no effect but log/sysprop)', dec_only),
                         ('neither', neither)):
        n = sum(r['lines'] for r in bucket)
        print(fmt % (name, len(bucket), n, 100.0 * n / total_lines if total_lines else 0))
    other = buckets(classify(lines, wide=not wide))[0]
    print('  %-38s %4d methods  %6d lines   <- wide-policy delta, %s'
          % ('  same bucket, POLICY %s' % ('narrow' if wide else 'wide'), len(other),
             sum(r['lines'] for r in other),
             'drop --wide-policy for the published number' if wide else 'pass --wide-policy'))

    declared = Counter()
    for line in lines:
        m = re.match(r'\s+(?:private|public|protected)?\s*static final '
                     r'(?:int|long|double|float|boolean) (\w+)', line)
        if m:
            declared[m.group(1)] += 1
    policy_declared = [k for k in declared if k not in WIRE_CONSTANTS]
    print()
    print('  DoD-1: policy constants declared here  %d  (target 0; %d wire/ABI exempt)'
          % (len(policy_declared), len(declared) - len(policy_declared)))
    if policy_declared:
        print('         ' + ' '.join(sorted(policy_declared)))

    if '--methods' in sys.argv:
        print('\nMIXED, largest first:')
        for r in sorted(mixed, key=lambda x: -x['lines']):
            print('  %5d  L%-6d %-34s %s' % (r['lines'], r['start'], r['name'],
                                             '+'.join(r['tags'])))
    if '--json' in sys.argv:
        json.dump(rows, sys.stdout)
    return 0


if __name__ == '__main__':
    sys.exit(main())
