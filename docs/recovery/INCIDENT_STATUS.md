# Aeolus recovery — incident status

**Wave:** first (Commander compare only — no product implementation)  
**Date:** 2026-10-23  
**Commander branch / worktree:** `recovery/integration` @ `D:\Project Aeolus-recovery-integration`  
**Charter:** Project store `docs/recovery/CHARTER.md`

Release state: **BROKEN**. 1.0.9 is a candidate, not a known-good base.

---

## Commits

| Role | Version | Commit | Evidence |
|------|---------|--------|----------|
| **Known-good** (last demonstrably usable core weather) | 1.0.6 / versionCode 7 | `122f07c` (`122f07cccf1048e95580d9c7956fe658939376d2`) | Pixel `57261FDCQ0011Q`: after Open-Meteo cooldown, live fetch produced a coherent bundle, wrote cache/LKG, UI went **LIVE**. Same session also proved the false-OFFLINE banner and wrong-location retain under 429. APK `dist/Aeolus-v1.0.6.apk`. |
| **Candidate** (current recovery tip — unproven) | 1.0.9 / versionCode 10 | `8ad7dc7` (`8ad7dc730ab01d847b0797d04cf67729bd540a1e`) | Diagnosis must-ship landed (stale/LKG, busy copy, covered-skip). Unit tests 128 / lintDebug / assembleRelease claimed. **Healthy-provider Today/Forecast/Models/LKG/offline matrix was not run.** Last install report: busy empty-online, LKG missing. APK `dist/Aeolus-v1.0.9.apk`. |
| Integration HEAD | candidate + this doc | `recovery/integration` (from `8ad7dc7`) | Tracking PR [#11](https://github.com/Discount152/Aeolus/pull/11) — **not a release**. Does **not** promote 1.0.9 to known-good. |

1.0.7 `c726dfe` / 1.0.8 `a67a042` are **not** known-good. Live bisect of “first bad LIVE city weather” is still blocked by provider 429. The first product change that makes a blank Today the default under 429 + empty disk is `c726dfe` (gate + wrong-location ban). That ban is **correct safety**; it is not the live trigger.

Do not treat `cursor/aeolus-1.0.9-must-ship-2996` or PR #10 as the recovery base.

---

## Integration decision (why HEAD is the candidate)

`recovery/integration` starts at `8ad7dc7`, not `122f07c`.

- Rolling the product back to 1.0.6 would reintroduce false-OFFLINE presentation and cross-location retain — both diagnosed as real 1.0.6 bugs.
- Agent C’s harness is specified from the 1.0.9 lineage.
- 1.0.9 already contains the diagnosis must-ship. Cherry-picking “everything 1.0.7–1.0.9” onto 1.0.6 is the wrong move; so is reverting 1.0.7–1.0.9 wholesale.

**Do not cherry-pick.** Keep the safety and 1.0.9 must-ship already on the candidate. Do not pull extra Map feature work as recovery. Do not merge PR #10 as a complete release.

---

## Lineage (1.0.6 → 1.0.9)

| Commit | What changed | Keep / hold |
|--------|--------------|-------------|
| `122f07c` 1.0.6 | LKG refuse-empty live overwrite. Last device-proven LIVE. | **Known-good snapshot.** Do not ship as-is (false OFFLINE + wrong-location retain). |
| `91707f1` | Ignore `dist/` / QA evidence folders. | Keep. |
| `f3cd7cb` 1.0.7 | Truthful connectivity (STALE vs OFFLINE from validated network). | **Keep.** |
| `8e286e2` | MapScreen generation, timeline, bottom bar. | **Hold as recovery.** Map work stays blocked until CORE WEATHER DEVICE_GREEN. |
| `c726dfe` 1.0.7 | `OpenMeteoGate` max 2; wrong-location ban; ephemeral no `findNearest`; no cross-location prior. | **Keep safety + gate.** Amplifies blank Today when LKG/cache are empty — that is honesty, not a rollback target. |
| `336ba82` | 1.0.7 solution note. | Keep. |
| `a67a042` 1.0.8 | Pin `timezone=auto`, ICON-first ephemeral, fewer map batches, playback, attribution. | **Hold Map extras.** ICON-only ephemeral is keep-if-already-present (reduces pin stampede). |
| `8ad7dc7` 1.0.9 | Matching-LKG stale; busy copy + 2s Retry guard; covered-skip / no empty `timeline_publish`; map yield on core/cooldown; `StaleOnRateLimitTest` + `MapCoveredSkipTest`. | **Keep must-ship.** Still unproven on a healthy provider. |

33 files / +1862 / −526 from `122f07c` to `8ad7dc7`. Do not flatten that into one recovery cherry-pick.

---

## Active defects

| ID | Defect | Owner | Notes |
|----|--------|-------|-------|
| D1 | CORE WEATHER not DEVICE_GREEN | B (wave 2) | No Pixel proof of LIVE Today/Forecast/Models, city + coordinate, rapid switch, offline restore, reconnect. |
| D2 | LastKnownGood missing on device | B / E | 1.0.8 and 1.0.9 captures: `lkgExists=false` / `size=0`. First-run blank under 429 is honest. Stale-on-429 path is unexercised until one coherent write. |
| D3 | Live Open-Meteo throttled | E (one probe) / all | Last independent probes: HTTP 429. `LIVE_PROVIDER_THROTTLED`. Not an Aeolus functional pass/fail. Do not hammer. |
| D4 | No DEBUG provider harness | C (wave 1) | Need SUCCESS_FULL, SUCCESS_CORE_ONLY, HTTP_429, HTTP_500, TIMEOUT, EMPTY_200, PARTIAL_MODEL_FAILURE, SLOW_SUCCESS through real repository/domain/UI. Production stays on real providers. |
| D5 | Map precip / covered-skip on device | D (blocked) / E | 1.0.9 code+unit tests exist. Radar READY / playable timeline **not claimed**. Map feature work blocked until D1. |
| D6 | Device QA rehearsal not yet closed | E (wave 1) | Commander did not drive the Pixel this wave. Last capture: 1.0.9 busy copy. |

---

## Agent ownership

| Agent | Role | Branch / worktree | Wave 1 |
|-------|------|-------------------|--------|
| **A** | Incident Commander | `recovery/integration` | Compare + this status. No product features. |
| **B** | Core Weather | (wave 2) | Today/Forecast/Models, OM core, caches, LKG, search, pin weather. No Map rendering. |
| **C** | Provider Harness | `recovery/provider-harness` from 1.0.9 lineage (`cursor/aeolus-1.0.9-must-ship-2996` @ `8ad7dc7` — record if they start from main instead) | DEBUG-ONLY deterministic provider. Checkpoint commit. No release PR. |
| **D** | Map | blocked | No significant Map work until CORE WEATHER DEVICE_GREEN. |
| **E** | Device QA | `recovery/device-qa` only if scripts added | Pixel `57261FDCQ0011Q`. Rehearse adb/logcat/screenshot/UI dump. `qa-device/runs/<timestamp>/`. Keyguard → `DEVICE_QA_BLOCKED_KEYGUARD`. One OM probe. |
| **F** | Release Reviewer | later | Adversarial. Reject until gates pass. `assembleRelease` is not proof. |

---

## Device QA state

| Item | State |
|------|-------|
| Device | Pixel 10 Pro XL, Android 17, serial `57261FDCQ0011Q` |
| Package | `com.discount152.aeolus` |
| Last reported install | 1.0.9 / versionCode 10 (`dumpsys package` in 1.0.9 execution report) |
| Last UI | Today/Forecast: `WEATHER SERVICE TEMPORARILY BUSY` / `Try again shortly.` / RETRY. Not OFFLINE. Not `HTTP 429`. |
| LKG | Missing (`exists=false size=0`) |
| This wave | Commander did not install, wipe, or probe. `adb` was not on PATH in the Commander shell. Agent E owns rehearsal. |
| Gate | Not DEVICE_GREEN. Not `DEVICE_QA_BLOCKED_KEYGUARD` (E has not reported). |

Capture: Project store `internal/aeolus-1.0.9-qa/` (and 1.0.8 `internal/aeolus-1.0.8-pipeline-capture/`).

---

## Provider state

| Item | State |
|------|-------|
| Last OM probe | HTTP **429** (1.0.8 diagnosis, repeated at 1.0.9 cut). No Retry-After observed on the 1.0.8 PC probe. |
| Classification | `LIVE_PROVIDER_THROTTLED` |
| Device Internet | Validated Wi-Fi + LTE during 1.0.8 capture — failure is not offline. |
| Rule | One live probe per wave (E). Do not confuse 429 with an Aeolus functional failure. |

---

## Release state

**BROKEN**

| Gate | Status |
|------|--------|
| BROKEN | **Current.** No complete release. No version bump. |
| CODE_GREEN | **Not declared for recovery.** Candidate `8ad7dc7` reported 128 unit tests, lintDebug SUCCESS, assembleRelease SUCCESS. Recovery has not re-run that suite on `recovery/integration`. |
| DEVICE_GREEN | **No.** |
| CORE WEATHER DEVICE_GREEN | **No.** Milestone 1 blocked. |
| MAP DEVICE_GREEN | Blocked on milestone 1. |
| VALIDATED_RELEASE | **No.** F has not reviewed. |

`assembleRelease SUCCESS` is not evidence the app works.

---

## Preserve (do not touch)

- **PR #10** — OPEN, MERGEABLE, head `cursor/aeolus-1.0.9-must-ship-2996`, base `cursor/aeolus-1.0.8-repair-36b5`. Leave open. Do not merge as VALIDATED_RELEASE. Do not force-push that branch.
- **PR #11** — tracking-only incident status on `recovery/integration`. Not a release.
- **APKs** `D:\Project Aeolus\dist\`:
  - `Aeolus-v1.0.0.apk` … `Aeolus-v1.0.6.apk` (1.0.6 SHA-256 `2f97a926b9356996722c23190a4481136559b94dce36f0e31fc89d89fdb1d258`)
  - `Aeolus-v1.0.7.apk` `a4ada65d6ce68fae831d0b1ab4eeb896e4d55d34ff4c2f46bc2d37454a82d586`
  - `Aeolus-v1.0.8.apk` `c77190a646b6e5e7430bc9a945cb44c81f026908e56e1e7a80b5b7915214c47b`
  - `Aeolus-v1.0.9.apk` `28af5bce81a98f27ab6751d7abd7caef1eb9187a88ece1c2f93089518c619c5f`
- History, tags, and prior release branches. No force-push.

---

## Wave 1 report

### STATUS

**FAIL** for release / DEVICE_GREEN. **PASS** for Commander first-wave deliverable (baseline compare + `recovery/integration` + this file).

### EVIDENCE

- 1.0.6 LIVE after OM cooldown: store `docs/aeolus-1.0.6-regression-root-cause.md`
- 1.0.8 global blank + 429 + missing LKG: store `docs/aeolus-1.0.8-pipeline-failure-root-cause.md`
- 1.0.9 must-ship vs diagnosis: store `docs/aeolus-1.0.9-spec-review.md`
- 1.0.9 shipped-but-unproven: store `docs/aeolus-1.0.9-execution-report.md`; UI dump `internal/aeolus-1.0.9-qa/ui-today-texts.txt`
- Git: `122f07c` → `8ad7dc7` is seven commits; PR #10 still open

### ROOT CAUSE

Live trigger remains **Open-Meteo HTTP 429 on the current egress IP** plus **empty LKG/cache** on the Pixel. 1.0.7 wrong-location / no cross-location retain is correct and leaves Today empty when both are true. 1.0.9 can show matching-LKG stale and honest busy copy, but the device still has no LKG and the provider is still 429, so core weather is still not DEVICE_GREEN. 1.0.9 is not a proven base.

### CHANGES

This wave: `git worktree add` `recovery/integration` from `8ad7dc7`; this status file in the Project store and on the integration branch. No product code, no version bump, no APK, no force-push, PR #10 untouched.

### DEVICE TEST

Not run by Commander. Last known: 1.0.9 installed, busy empty-online, LKG missing, OM 429. Agent E owns rehearsal.

### REMAINING

1. C: DEBUG harness checkpoint (do not open a release PR).  
2. E: device rehearsal + `qa-device/runs/<timestamp>/`; one OM probe; keyguard honesty.  
3. Wave 2 / B: deterministic core matrix, then Pixel core matrix → CORE WEATHER DEVICE_GREEN.  
4. D: Map only after that.  
5. F: reject until CODE_GREEN + DEVICE_GREEN.  
6. Do not merge PR #10 as complete. Do not hand the user an APK to discover whether Today works.
