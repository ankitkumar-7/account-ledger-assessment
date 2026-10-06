# AMBIGUITIES

Each entry gives the ambiguity, the readings considered, the resolution, and whether the choice
changes any number in the output.

---

## Time and ordering

### A1. What does the "Day N" column on each event mean?
Each event has a day and a value_date that sometimes differ (E7: Day 5, value Day 2).
**Resolution:** the event's day is the *processing day*, when the ledger learns of it. The
value_date decides which day's balance it belongs to. Every journal stores both (`bookedDay`,
`valueDay`). This is the central modelling decision (DECISIONS.md D1).

### A2. E10 is stamped Day 5 but listed after E9 (Day 6).
Readings: (a) the stream is meant to be sorted by day and E10 is out of place; (b) E10 really does
arrive after E9 — a late-arriving event; (c) the stamp is a typo for Day 6.
**Resolution:** (b). The brief says "replayed in this order", so the processing clock is already on
Day 6 when E10 arrives. It is processed on Day 6, flagged `LATE` in the output, and value-dated
Day 5 as stated. Day 5's report therefore does not show it; Day 6's report shows Day 5's restated
ACC-002 closing (10.000*). **Effect on numbers:** none for ACC-002's final figures — interest is
computed on restated balances, so Day 5 still earns 0.004.

### A3. When within a day are fees assessed?
**Resolution:** at end of day, after all events processed that day. So Auth-B (E8, Day 5) is
decided before Day 5's fees are booked. **Effect:** none on the outcome — available is −245.00
without the fees and would be lower with them.

### A4. What does "evaluated at end of Day 5 and before any fee is assessed" (criterion 1) mean exactly?
**Resolution:** after the last Day 5 event (E8) and before Day 5's end-of-day fee run. E10 (stamped
Day 5) has no effect either way: different account, and it arrives after E9 (A2).

### A5. Which "closing ledger balance" does the per-day report show?
After E7 and E9, a past day's closing balance changes. **Resolution:** each day's report prints the
closing balance of that day as known at that point, *and* the restated closing balance of every
earlier day, marking with `*` any value that changed since it was last reported. This shows both
what the bank believed at the time and what it believes now.

### A6. Forward-dated entries.
No event has a value_date after its processing day. **Resolution:** refused, with an error. Supporting
them would mean a "future" balance that today's authorizations must or must not see — out of scope.

---

## Overdraft fee

### A7. "Booked with value_date equal to the day assessed" — which day?
Readings: (a) the day whose closing balance is negative (Day 2 for E7); (b) the processing day on
which the fee is computed (Day 5).
**Resolution:** (a). A fee "for" Day 2 belongs on Day 2. Reading (b) would put three fees on Day 5
against "once per day per account", and leave Days 2 and 4 negative with no fee recorded against
them. **Effect:** large — (a) gives 3 fees; (b) gives at most one.

### A8. Can a fee be assessed for a day that has already ended?
Day 2 had ended when E7 arrived on Day 5. Readings: (a) fees are only ever assessed for the day
just ending; (b) each end of day re-checks every day up to today.
**Resolution:** (b). The rule defines the trigger as "that day's closing ledger balance (all entries
with value_date ≤ that day)", which back-valued entries can change after the day. Reading (a) would
let a back-valued debit escape the fee rule entirely. Each end of day checks Days 1..today, in order.
**Effect:** fees on Days 2 and 4 exist only under (b).

### A9. Does a fee count towards later days' balances (cascade)?
A fee is itself a negative entry value-dated to its day, so it lowers every later closing balance.
**Resolution:** yes. Days are checked in order, so the Day 2 fee is included when Day 3 is checked.
**Effect:** Day 3 is +5.00 with the cascade, +30.00 without — no fee either way here, but the margin
is only 5.00 (see NUMBERS.md).

### A10. "Once per day per account" — per value day or per processing day?
**Resolution:** per value day: at most one OVERDRAFT_FEE journal per account per value day, however
many times that day is re-checked.

### A11. The fee is in AED. What about the BHD account?
ACC-002 never goes negative here, so this is not exercised. Readings: convert at a rate; charge a BHD
equivalent; skip. **Resolution:** the ledger has no FX rate, and a cross-currency journal cannot
balance without FX legs. If a BHD account goes negative, the fee is not booked and an error is
recorded instead. Tested with a synthetic case.

### A12. Should reversing E7 refund the fees it caused?
**Resolution:** no — see REJECTED.md criterion 6 and DECISIONS.md D9. Flagged as the biggest
customer-facing consequence of this design (the failing test).

---

## Interest

### A13. Interest on which balance — as known each evening, or restated?
Readings: (a) each day's closing balance as known at that day's end; (b) each day's closing balance
as restated by every back-valued entry booked by capitalisation time.
**Resolution:** (b), computed once at end of Day 6 (DECISIONS.md D10). Consistent with the fee rule's
definition of closing balance; banks make back-valued interest adjustments the same way.
**Effect:** ACC-001 0.93 under (b) vs 0.81 under (a).

### A14. Day 6's accrual — before or after capitalisation?
**Resolution:** before. Day 6 accrues on 390.00; the 0.93 credit is then booked, value-dated Day 6.
Otherwise interest would earn interest on the same day it is credited.

### A15. Rounding mode for daily accruals.
Not specified. **Resolution:** HALF_EVEN (banker's rounding), see NUMBERS.md. **Effect:** none here —
no daily accrual lands exactly on a half (0.166, 0.156 and 0.186 are not ties).

### A16. Are fees "closing ledger balance" for interest?
**Resolution:** yes. Fees are booked journals, and the closing balance is all entries. Days 2–5
therefore earn less interest because of the fees.

---

## Authorizations and settlements

### A17. What counts as "ledger balance" in the authorization check?
**Resolution:** the current ledger balance — every journal booked so far, including back-valued
ones. At E8 it is −155.00 because E7 is already booked. Using "closing balance of the auth's value
date" gives the same figure here.

### A18. What does an authorization's value_date mean?
A hold moves no money, so it has no place in the value-dated balance. **Resolution:** the value_date
is validated and recorded but has no effect on balances.

### A19. Settlement for less than the hold (Auth-A: 185.00 of 200.00).
Readings: release the whole hold (single capture), or keep 15.00 held for a further capture
(multi-capture). **Resolution:** single capture: the settlement consumes the authorization and
releases the full 200.00 hold.

### A20. Settlement for more than the hold.
Not in the stream. **Resolution:** refused (tolerance 0%, NUMBERS.md). Real schemes allow a
tolerance for tips and some merchant types; there is no merchant data here to justify one.

### A21. Settlement against a declined or already-settled authorization.
**Resolution:** refused, no funds moved — same treatment as an unknown authorization.

### A22. "Not present in the ledger" (criterion 4) — never existed, or no longer active?
In production these differ: an authorization can expire and be purged before a legitimate late
settlement arrives. **Resolution:** this model never expires authorizations, so "not present" means
"never existed". See REJECTED.md criterion 4 for why "any" is too strong in production.

### A23. "Auth-B is never settled inside the window."
Suggests the brief expects Auth-B to be approved and the hold left hanging. **Resolution:** Auth-B
is declined (A17), so it holds nothing. There is no hold expiry in the model; a hold approved and
never settled would stay active indefinitely (an architecture-document topic).

### A24. Are debits checked against available balance?
Only authorizations are gated by the rule. E2 and E7 are posted debits. **Resolution:** posted
debits are booked as facts even if they overdraw the account — which is exactly what makes the
overdraft fee meaningful.

---

## Reversals and instalments

### A25. What does a reversal undo?
**Resolution:** the money movement of the target journal only: a new REVERSAL journal with every
posting negated, referencing the original. Only CREDIT and DEBIT journals can be reversed; a
journal can be reversed once. Settlements, fees and interest are not reversible this way (a
settlement reversal would also have to reason about the authorization).

### A26. A reversal's value_date different from the original's.
E9 uses Day 2, matching E7. **Resolution:** the reversal uses its own stated value_date. If it
differed, the account would be overdrawn (or in credit) for the days in between — correct for a
reversal that genuinely happens later.

### A27. "Three equal instalments" of 10.000 BHD.
Impossible at three decimal places. **Resolution:** 3.333 + 3.333 + 3.334: parts rounded down,
remainder on the last. All three are legs of a single journal, so they post atomically.

### A28. Three instalments: one transaction or three?
**Resolution:** one journal, three customer postings. They share one event, one value date and one
booking, so they should succeed or fail together. Recorded as separate legs so each instalment is
visible.

---

## Accounts and data

### A29. How is an opening balance represented?
Both are zero here. **Resolution:** a non-zero opening balance would be booked as a credit journal
against external clearing, value-dated the day before the window, so it is auditable and the books
still balance.

### A30. What happens to an amount with too many decimal places?
"Amounts stored and rounded to their own precision" could mean round on input. **Resolution:** input
amounts with excess precision are refused, not rounded. Only interest and instalment splitting
round, explicitly, because those are the only places a sub-unit amount is a legitimate
intermediate result. Silently rounding a payment amount would hide an upstream error.

### A31. Duplicate event ids.
Not in the brief. **Resolution:** rejected as duplicates (DECISIONS.md D7) — a retry must never
post twice.
