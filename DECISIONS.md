# DECISIONS

Architecture decision log. Each entry: context, options considered, decision, consequences.
Production implications are expanded in the Architecture & Trade-offs document; this file records
what was decided *for this build* and why.

---

## D1 — Bitemporal, append-only ledger

**Context.** Events arrive on one day but can be value-dated to an earlier day (E7 arrives Day 5,
belongs to Day 2). "What was Day 2's balance?" and "what did we believe Day 2's balance was on
Day 3?" have different answers, and both must be answerable.

**Decision.** Every journal carries two time axes:
- `valueDay` — business time: which day's balance it belongs to.
- `bookedDay` + a global monotonically increasing `sequence` — system time: when it was written.

No record is ever updated or deleted. Corrections are new records (a REVERSAL journal references
the journal it reverses). All state — balances, holds, authorization status — is derived from the
records.

**Consequences.** Back-valued entries are correct by construction: a closing balance is a fold over
`valueDay ≤ d`. History cannot be lost. The cost is that every derived value must be computable from
the log, and the log grows forever (see D4, and the architecture document).

---

## D2 — Double-entry journals

**Context.** A single signed posting per customer movement is simpler, but money then appears or
disappears with no counter-side: nothing shows where a fee went or where interest came from, and
there is no structural check that the books are complete.

**Options.**
1. Single-entry signed postings on customer accounts.
2. Double-entry: each business transaction is a *journal* of two or more *postings* whose signed
   amounts sum to zero per currency, across customer and internal (GL) accounts.

**Decision.** Option 2. Internal accounts, one set per currency:

| Internal account | Used by |
|---|---|
| `GL-EXTERNAL_CLEARING-<ccy>` | external credits and debits (E1, E2, E4, E7, E10) |
| `GL-CARD_SCHEME_SETTLEMENT-<ccy>` | card settlements (E5) |
| `GL-FEE_INCOME-<ccy>` | overdraft fees |
| `GL-INTEREST_EXPENSE-<ccy>` | interest capitalisation |

Sign convention: amounts are signed, positive = credit. A customer account's balance is the sum of
its postings, i.e. what the bank owes the customer. A journal is refused unless its postings sum to
exactly zero in each currency.

**Consequences.**
- Global invariant: the sum of every posting in the ledger is zero per currency. The test suite
  asserts it after the full replay — a cheap, strong audit control.
- Fee income and interest expense are visible as balances.
- Cross-currency journals cannot balance without FX legs. That is exactly why an AED-denominated
  overdraft fee on the BHD account cannot be booked without an FX rate (see AMBIGUITIES.md).
- More concepts than the brief strictly needs; accepted for the control it buys.

---

## D3 — Holds are not postings

**Context.** An authorization reserves funds but moves no money.

**Decision.** Authorizations live in a separate append-only lifecycle log (`AuthRecord`: APPROVED,
DECLINED, SETTLED). Holds affect *available* balance only; they never touch ledger balance. This
mirrors the two-phase "pending transfer" model: authorization = pending, settlement = post the
pending transfer (possibly for less), release = void it.

**Consequences.** Ledger balance stays a pure fold over money movements. Criterion 5's invariant
("a hold reduces available but not ledger balance") holds structurally rather than by care.

---

## D4 — Balances: write-through cache for current balances, fold for daily closings

**Context.** Balances can be derived (fold over postings on every read) or materialized (stored
and updated on each posting).

| | Derived fold | Materialized / cached |
|---|---|---|
| Correctness | always right by construction | can drift; needs reconciliation |
| Back-valued entries | trivial — refold | stored daily closings must be restated from the value day onward |
| Read cost | O(entries), grows forever | O(1) |
| Concurrency | read-only | balance becomes a contention point |
| Audit | one source of truth | two truths that must agree |

**Decision.** Hybrid:
- **Current ledger balance and active holds** per account are held in a *write-through cache*
  updated inside the same atomic append that writes the journal or authorization record.
  Authorization decisions read from this cache. A back-valued entry does not invalidate it, because
  "current" balance includes every entry regardless of value date.
- **Daily closing balances** (value-dated, used only by end-of-day fee assessment and interest) are
  derived by folding the log, because back-valued entries rewrite them.
- **`reconcile()`** recomputes every cached value from the log and reports any mismatch. The tests
  run it after every event.

**Consequences.** Demonstrates the production pattern (cached balance + reconciliation) at small
scale. The rule this encodes: *a cache that feeds a decision must update in the same atomic unit as
the log*. A cache in a separate store (e.g. Redis beside a database) creates a dual-write problem:
the posting commits, the cache update fails or lags, and the next authorization is approved against
a stale balance. Display-only caches may be eventually consistent; decision caches may not.

---

## D5 — Concurrency: single writer

**Context.** The authorization rule is a check-then-act: read available balance, then place a hold.
Two concurrent authorizations of 60 against 100 available can both read 100 and both be approved
(a TOCTOU race).

**Options.** Single writer per account (actor / partition by account id); pessimistic row lock
(`SELECT … FOR UPDATE`); optimistic version check with retry.

**Decision.** The engine is the single writer. Its event-application entry point is `synchronized`,
so each event's validation, decision and append happen as one atomic step. Read-only queries are
intended for after (or between) event application. In production the equivalent is a single writer
per account partition, which keeps ordering deterministic and needs no row locks.

**Consequences.** Correct and simple here; throughput is bounded by one writer. Partitioning by
account preserves correctness for single-account events; multi-account transfers would need a
coordination protocol (not in scope).

---

## D6 — Each event is all-or-nothing

**Decision.** An event is fully validated and its whole journal built before anything is appended.
Rejected events append nothing to the money ledger; they append a `Rejection` record. E10's three
instalments are three postings in *one* journal, so they land together or not at all.

---

## D7 — Idempotent event ids

**Context.** Not required by the brief, but retries and replays are the normal failure mode of any
event-driven ledger, and a double-posted debit is a real-money incident.

**Decision.** The engine records every event id it has seen (accepted or rejected). A second event
with the same id is rejected as a duplicate and changes nothing.

---

## D8 — Decisions record their inputs

**Context.** Auditors and customers ask "why?": why was Auth-B declined, why was I charged on Day 4.

**Decision.**
- Every authorization record stores the ledger balance, active holds and resulting available
  balance used to make the decision.
- Every overdraft-fee journal stores the closing balance that triggered it.
- Every rejection stores a human-readable reason.

**Consequences.** Any decision can be explained from the record alone, without replaying history.

---

## D9 — Fees stand after a reversal

**Context.** E9 reverses E7 after three fees were assessed because of E7.

**Decision.** Fees stand. They were assessed correctly on the facts booked at assessment time, and
no rule authorises an automatic refund. Refunding would be a policy decision (and, in production,
likely a customer-fairness obligation) that belongs in a fee-waiver workflow, not in silent ledger
behaviour. The annotated failing test exposes the consequence.

---

## D10 — Interest computed on restated balances at capitalisation

**Decision.** At the end of Day 6, each day's accrual is computed on that day's closing balance as
restated by every back-valued entry booked by then. This is consistent with the fee rule's own
definition of closing balance ("all entries with value_date ≤ that day") and with back-valued
interest adjustment as practised by banks.

---

## D11 — Java 21, zero dependencies

**Decision.** `BigDecimal` for money, a small in-repo test harness, a shell script to compile and
run. Anyone with a JDK can run the suite; no build tool is needed.
