# WORKLOG

Times are local (IST). AI assistance (Claude Code) is noted where used, as permitted by the brief.

## 2026-10-06

- **23:00** Read the brief (Part 1 build + Part 2 architecture document).
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