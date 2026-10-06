# REJECTED

Part 1 lists the acceptance criteria refused, with reasons. Part 2 lists criteria accepted with a
caveat. Part 3 lists approaches abandoned mid-build.

Every refusal is backed by a passing test that asserts the behaviour that replaces it
(`./run.sh test`, tests prefixed "criterion N refused").

---

## Part 1 — Refused criteria

### Criterion 2 — "E7 causes exactly one overdraft fee to be assessed, on Day 2."

**Refused.** E7 is a 620.00 debit value-dated Day 2. Because closing balances are cumulative
(every entry with value_date ≤ the day), it lowers the closing balance of Day 2 *and every day after
it*, not just Day 2.

Closing balances at the end of Day 5, before fees:

| Day | Before E7 | After E7 |
|---|---|---|
| 2 | 250.00 | **−370.00** |
| 3 | 650.00 | 30.00 |
| 4 | 465.00 | **−155.00** |
| 5 | 465.00 | **−155.00** |

Assessing in day order, each fee value-dated to its day:
- Day 2: −370.00 → fee → −395.00
- Day 3: −395.00 + 400.00 = **+5.00** → no fee
- Day 4: 5.00 − 185.00 = −180.00 → fee → −205.00
- Day 5: −205.00 → fee → −230.00

E7 causes **three** fees (Days 2, 4, 5), 75.00 in total.

The criterion fails under the other reading of the fee rule too. If "booked with value_date equal to
the day assessed" meant the processing day (Day 5), then "once per day per account" would allow only
one fee on Day 5 — one fee, but on Day 5, not Day 2. There is no reading under which the criterion
holds as written.

### Criterion 6 — "After E9, all balances and fees return to their pre-E7 values."

**Refused.** Three independent reasons:

1. **Append-only.** The three fee journals exist and cannot be deleted. E9 is a new REVERSAL
   journal that mirrors E7's postings; it reverses E7's money movement, not E7's consequences.
   Fees are their own transactions and nothing reverses them.
2. **No rule authorises a fee refund.** The fee rule defines when a fee is assessed, not when it is
   refunded. The fees were correctly assessed on the facts booked at the time (DECISIONS.md D9).
   After E9 the balances are: Day 2 225.00 (pre-E7 250.00), Day 4 415.00 (pre-E7 465.00).
3. **Decisions are path-dependent.** Without E7, Auth-B would have been approved (465.00 − 90.00 =
   375.00). With E7 present, it was declined (−155.00 − 90.00 = −245.00). A decline cannot be
   un-declined. Even if every fee were refunded, the customer's state would differ from pre-E7 —
   there would be no active 90.00 hold.

Even a design that auto-refunds fees could not satisfy this criterion because of reason 3. The
annotated failing test (`reversal restores the customer to the pre-E7 position`) demonstrates the
gap: 390.93 actual vs 466.03 counterfactual.

### Criterion 7 — "The three BHD instalments in E10 must each be BHD 3.334."

**Refused.** 3 × 3.334 = 10.002, which is 0.002 more than the 10.000 credited. Accepting it would
create money. At three decimal places, 10.000 cannot be split into three equal parts; the closest
is 3.333 + 3.333 + 3.334 = 10.000. The ledger divides and rounds down to get the first parts and
puts the remainder on the last. A double-entry journal with 3 × 3.334 against a 10.000 counter-leg
would not balance and would be refused by the ledger anyway.

### Criterion 8 — "If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded."

**Refused.** It contradicts a non-negotiable rule: "The rounded daily accruals must sum exactly to
the capitalized total." The design makes the mismatch impossible instead of handling it: the
capitalised total *is* the sum of the rounded daily accruals. There is never a remainder.
"Discarding" a remainder would also mean losing money from the books (the double-entry ledger would
not balance). ACC-001: 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 = 0.93 capitalised.

---

## Part 2 — Accepted with a caveat

### Criterion 1 — "The Day 2 closing ledger balance, evaluated at end of Day 5 and before any fee is assessed, is AED −370.00."

**Accepted.** 1,200.00 − 950.00 − 620.00 = −370.00. E9 (also value-dated Day 2) has not arrived yet.

### Criterion 3 — "The Day 4 settlement of Auth-A must be accepted."

**Accepted.** Auth-A was approved for 200.00; it settles for 185.00. Settling for less than the
hold is normal (final amount lower than estimated). The 185.00 is debited and the whole 200.00 hold
is released; the unused 15.00 is not kept on hold (single capture).

### Criterion 4 — "Any settlement referencing an authorization ID not present in the ledger must be rejected and the funds must not leave the account."

**Accepted for this model, but the word "any" is too strong for production.** E6 (Auth-Z) is
rejected, recorded as an error, and posts nothing. In real card processing, settlements without a
matching authorization are legitimate in several cases: offline or force-posted transactions,
presentments arriving after the hold has expired and been purged, and some recurring merchants. The
issuer is usually already liable to the scheme for the amount, so "the funds must not leave the
account" moves the loss to the bank rather than avoiding it. Production would route these to an
exception queue (suspense account plus review or chargeback), not reject them outright. The
architecture document covers this under the authorization lifecycle.

### Criterion 5 — "If Auth-B is approved, its hold reduces available balance but not ledger balance."

**Accepted as an invariant; its premise is false in this stream.** Auth-B is **declined**: at the
moment of E8, the ledger balance is −155.00 (E7 has been booked), so available after the hold would
be −245.00. The criterion is a conditional whose condition never occurs, so it cannot be verified on
the given stream. It is proved with a synthetic approved authorization instead (holds are a
separate, off-ledger record, so they cannot affect ledger balance — DECISIONS.md D3). Noted because
it reads as if it expects Auth-B to be approved.

---

## Part 3 — Approaches abandoned mid-build

1. **Single-entry signed postings.** The first commit modelled each customer movement as one signed
   entry. Abandoned for double-entry journals (DECISIONS.md D2): single-entry has no counter-side
   (no record of where fee income or interest expense goes) and no structural "books balance"
   control. Visible in the history: `Add append-only ledger store…` then `Move to double-entry…`.
2. **Pure fold for every balance.** The first ledger recomputed every balance from the full log on
   every read. Replaced with a write-through cache for current ledger balance and holds, plus
   `reconcile()` checked after every step (D4). Kept the fold for value-dated daily closings,
   because back-valued entries rewrite them.
3. **Opening balance as a field on the account.** Abandoned: in a double-entry ledger an opening
   balance with no counter-posting is an unbalanced amount. Non-zero opening balances are now
   ordinary credit journals against external clearing.
4. **Fee value-dated to the processing day.** Considered and rejected: it would book all three fees
   on Day 5, collide with "once per day per account", and leave Days 2 and 4 showing negative
   balances with no fee against them.
5. **Auto-refunding fees on reversal.** Considered and rejected (D9). It satisfies no rule, would
   still not meet criterion 6 (Auth-B), and is a policy decision rather than ledger mechanics.
6. **Interest on balances as known each evening.** Considered and rejected (D10). It would give
   0.81 instead of 0.93, ignore value dating, and contradict the fee rule's own definition of a
   closing balance.
7. **Sorting events by their stamped day.** Rejected: the brief says to replay in the given order.
   Sorting would hide the late arrival of E10 rather than handle it.
8. **Maven/Gradle with JUnit.** Dropped: no build tool on the machine, and a JDK-only project is
   easier for any reviewer to run.
