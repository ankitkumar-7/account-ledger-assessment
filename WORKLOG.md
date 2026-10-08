# WORKLOG

Times are local (IST). AI assistance (Claude Code) is noted where used, as permitted by the brief.

## 2026-10-06

- **Before 23:05** Read the brief (Part 1 build + Part 2 architecture document).
- **23:05–23:23** Design pass with AI assistant: hand-replayed the event stream on paper, day by day,
  separating processing day from value date. Key findings:
  - E7 (backdated to Day 2) turns Day 2, Day 4 and Day 5 negative, so three overdraft fees, not one.
  - Day 3 lands on exactly +5.00 after the Day 2 fee — no cascade fee, but only just.
  - Auth-B is declined (available −245.00 at the time), so criterion 5's premise never holds.
  - 3 × 3.334 = 10.002, so criterion 7 is arithmetically wrong.
  - Criterion 8 contradicts the "must sum exactly" rule.
- **23:23** Decisions: fees stand after the E9 reversal (no rule authorises a refund); interest is
  computed at capitalisation on value-date-restated balances; Java 21 with zero dependencies so the
  suite runs with only a JDK (no Maven/Gradle on this machine). Initialised the repository and
  removed the IDE-generated sample code.
- **23:27** First domain layer committed: single-entry signed entries, append-only store, balances
  folded from the log, over-precise amounts refused.
- **23:28–23:41** Stopped coding to review the architecture before going further: single- vs
  double-entry, a stored vs derived available balance, concurrency (the check-then-hold race on
  authorizations), all-or-nothing events, auditability. Decided:
  - Double-entry journals against internal GL accounts, for the zero-sum invariant and a visible
    counter-side for fees and interest.
  - My suggestion of a balance cache, refined with the AI assistant into: a write-through cache
    for current balance and holds, updated in the same atomic append (a cache that feeds a decision
    must never be updated separately from the log), daily closings still folded, and a
    `reconcile()` check after every step.
  - A single writer (`synchronized` engine); idempotent event ids; decision inputs stored on
    authorization and fee records.
  Wrote these up in DECISIONS.md.
- **23:44** Reworked the model to double-entry and added the engine (fees across back-valued days,
  interest on restated balances, settlements, reversals, instalments).
- **23:45** Replay driver and per-day report. First full run matched the hand replay exactly: three
  fees, Day 3 at +5.00, Auth-B declined, ACC-001 390.93, ACC-002 10.008.
- **23:47** Test suite (17 passing) and the intentional failing test: the reversal does not restore
  the pre-E7 position (390.93 vs 466.03 counterfactual; Auth-B approved without E7). Replaced a
  sloppy Day 3 assertion with a proper end-of-Day-5 checkpoint (`runThroughEndOfDay`).
- **23:49** REJECTED.md, AMBIGUITIES.md, NUMBERS.md, README. While writing NUMBERS.md I checked the
  sensitivity of the given fee: at 50.00 Day 3 would go to −20.00 and cause a fourth fee, which is
  why fees are assessed day by day in order.

## 2026-10-07

- **00:53–01:42** Walked through the code file by file with the AI assistant to make sure I can
  explain every part without it: the layering (replay drives time, engine decides, ledger records),
  the precision gate, journal structure and sign convention, the write-through cache and
  `reconcile()`, the fee loop's day-by-day cascade, interest capitalisation and the replay clock.
  Noted weak spots to own in the defense: an event id is marked as seen before validation (a
  rejected id cannot be reused), a settlement is two appends that are only atomic because of the
  single writer, and in-process reconciliation catches cache-logic bugs but not storage corruption.
- **00:59** The IDE showed unresolved symbols everywhere although `./run.sh` compiled cleanly.
  Cause: IntelliJ still treated `src/` as the source root and only had JDK 17 registered. Added a
  local module configuration (`src/main/java` and `src/test/java` as roots, Java 21). IDE files are
  gitignored, so the repository is unchanged.
- **02:01** Ran the replay and the suite end to end and re-read the day-by-day output against my
  hand replay.
- **02:02–02:05** Architecture & Trade-offs document (Part 2), drafted with the AI assistant from
  the decision log, REJECTED.md and the code: the first bottleneck at 100× volume (the end-of-day
  fee run scans every journal for every account and every day, not memory), the per-value-day index
  as the cheapest fix, the back-valuation approval gate as the control for value-dated entries, every
  way an authorization ends without a matching settlement (in this model only DECLINED and SETTLED
  end one; everything else leaves a hold forever), and what was cut. Written as HTML and rendered
  to a 3-page PDF with headless Chrome.

## 2026-10-08

- **16:23–16:36** Prepared the submission. The commits carried my work identity, because this
  laptop's global git config is set up for work. Before the first push I rewrote the author and
  committer name and email on all commits to my personal identity, keeping every original
  timestamp and message (checked against a snapshot taken before the rewrite; no commits squashed or
  dropped). Set the identity locally for this repository only. Created the public GitHub repository
  and pushed over HTTPS with a short-lived token, leaving the laptop's work GitHub login and SSH
  key untouched.
- **16:36–16:41** Verified the push: the remote head matches local, all commits are present with
  the right author and dates, and a fresh clone runs (`17 passed, 1 failed (0 unexpected)`, final
  balances 390.93 AED and 10.008 BHD).
- **16:39** Removed the outdated "Next" list from this log.
- **16:43** Filled in the missing entries above, using timestamps from the AI session log, the git
  reflog and the push log rather than memory.
