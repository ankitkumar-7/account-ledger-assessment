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
