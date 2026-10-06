# NUMBERS

Every constant in the build. For each: where it lives, why that value, and what would happen at
half of it (or why halving makes no sense). Constants given by the brief are listed separately from
the ones I chose, because only the second group is mine to defend — but how the given ones interact
is part of the design too.

All business constants are in `src/main/java/ledger/Rules.java` unless stated otherwise.

---

## Constants I chose

### N1. Accrual precision = the currency's own precision (AED 0.01, BHD 0.001)
Each daily accrual is rounded to 2 dp (AED) or 3 dp (BHD) before summing.
- **Why:** the brief says amounts are stored and rounded to their own precision, and that the
  *rounded* daily accruals must sum to the capitalised total. That only means something if each
  accrual is rounded to the currency's precision.
- **Why not finer** (e.g. 4 dp accruals, rounded once at capitalisation): that is a valid
  production design and loses less to rounding, but then there are no "rounded daily accruals" to
  sum — it contradicts the rule.
- **Why not half the precision** (AED to 0.1): Day 2's 0.09 becomes 0.1, and the result is no
  longer an AED amount at AED precision.

### N2. Accrual rounding: HALF_EVEN (banker's rounding)
- **Why:** across many accounts and days, HALF_UP rounds every exact half upward, a systematic bias
  in the bank's interest expense. HALF_EVEN rounds halves to the even neighbour, so the bias
  averages out.
- **Effect here:** none. The unrounded accruals are 0.1, 0.09, 0.25, 0.166, 0.156, 0.156 (AED) and
  0.004, 0.004 (BHD). None is an exact half, so HALF_UP gives the same 0.93 and 0.008.
- "Half" does not apply to a rounding mode.

### N3. Settlement-over-authorization tolerance: 0%
A settlement may not exceed its hold.
- **Why:** the authorization rule approves an amount against available balance. A settlement above
  the hold spends money nobody checked. Real schemes allow tolerances (e.g. for restaurant tips),
  but they depend on merchant category, which this model does not have.
- **Why not half of a typical tolerance** (say 10% instead of 20%): any positive number is
  arbitrary without merchant data. Zero is the only value the authorization rule itself justifies.
  Half of zero is zero.

### N4. Instalment split: round each part DOWN to currency precision, remainder on the last part
10.000 / 3 = 3.333… → 3.333, 3.333, and the last = 10.000 − 6.666 = 3.334.
- **Why down:** it guarantees the remainder is ≥ 0, so the last part is never smaller than the
  others and never negative. HALF_UP happens to give the same split here (3.333… rounds down
  anyway), but in general it can make the last part smaller (2.00 / 3 → 0.67, 0.67, 0.66) or even
  negative (0.11 / 7 → six parts of 0.02 = 0.12, last part −0.01). Down is safe for all inputs.
- **Why the last part:** a convention; it keeps the first instalments identical. Putting it first
  is equally valid.

### N5. Maximum overdraft fees per account per value day: 1
- From the rule ("once per day per account"), applied per *value* day (AMBIGUITIES.md A10).

### N6. Fee look-back: all days from Day 1 to today, on every end of day
- **Why:** a back-valued entry can make any earlier day negative (E7 reaches back three days). In a
  six-day window, checking all of them is cheap and complete.
- **Why not half** (look back 3 days): E7 is booked Day 5 for Day 2 — exactly 3 days back. A
  2-day look-back would miss the Day 2 fee entirely. In production this must be a bounded,
  regulated value (a back-valuation limit), not "everything" — see the architecture document.

### N7. Opening-balance value day: Day 0 (one day before the window)
- Only used for a non-zero opening balance (none here). Day 0 means the opening balance is part of
  every day's closing balance and never earns or triggers anything for a day outside the window.

### N8. Hold expiry: none
- **Why:** the brief defines no expiry and the window is six days. Auth-A settles in two days.
- **Production:** expiries are typically about 7 days (longer for hotels and car rental). Half
  (3–4 days) would expire legitimate holds before slow merchants settle, producing settlements
  against expired authorizations — the criterion 4 problem.

---

## Constants given by the brief, and how they interact

### G1. Overdraft fee: AED 25.00
Not mine to choose, but its size decides how many fees there are:
- After E7, Day 3's closing balance is −370.00 − 25.00 + 400.00 = **+5.00**. The fee is only
  5.00 short of tipping Day 3 negative.
- **At half (12.50):** Day 3 = +17.50. Still three fees, total 37.50.
- **At double (50.00):** Day 3 = −20.00, so a fourth fee (a cascade): Days 2, 3, 4, 5.
- So the fee count is not proportional to the fee size. This is why fees are assessed day by day in
  order, each one included in the next day's check, instead of counting negative days in one pass.

### G2. Daily interest rate: 0.04% = 0.0004 per day
- Held exactly as `0.0004`. It is *not* derived from an annual rate (0.0004 × 365 = 14.6% p.a.),
  so there is no day-count convention (ACT/365, ACT/360) to choose.
- **At half (0.0002):** ties appear and the rounding mode starts to matter. Day 2:
  225.00 × 0.0002 = 0.045, which rounds to 0.04 under HALF_EVEN but 0.05 under HALF_UP. At the
  given rate there are no ties, so N2 does not change the output.

### G3. Currency precision: AED 2 dp, BHD 3 dp
- ISO 4217 minor units. Enforced in `Currency.java`; amounts with excess precision are refused
  (AMBIGUITIES.md A30).

### G4. Window: Day 1 to Day 6
- `Rules.FIRST_DAY`, `Rules.LAST_DAY`. Interest is capitalised at the end of `LAST_DAY`.

---

## Resulting numbers (for checking the output)

| Figure | Value | Derivation |
|---|---|---|
| ACC-001 Day 2 closing at end of Day 5, before fees | −370.00 | 1,200 − 950 − 620 |
| Auth-A available after hold | 50.00 | 250 − 200 |
| Auth-B available after hold (declined) | −245.00 | −155 − 90 |
| Fees | 75.00 | Days 2, 4, 5 × 25.00 |
| ACC-001 restated closings Days 1–6 | 250.00, 225.00, 625.00, 415.00, 390.00, 390.00 | after E9, fees stand |
| ACC-001 accruals | 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 = 0.93 | × 0.0004, HALF_EVEN |
| ACC-001 final | 390.93 | |
| ACC-002 instalments | 3.333 + 3.333 + 3.334 = 10.000 | |
| ACC-002 accruals | 0.004 (Day 5) + 0.004 (Day 6) = 0.008 | |
| ACC-002 final | 10.008 | |
| Counterfactual ACC-001 final without E7/E9 | 466.03 | 465.00 + 1.03 (failing test) |
