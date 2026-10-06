# In-memory account ledger core

A six-day, in-memory, append-only, double-entry ledger for an AED and a BHD account. It replays a
fixed event stream and prints, for each day, the closing ledger balances, fee assessments,
authorization states and errors. No web layer, no persistence, no database.

## Requirements

A JDK, version 21 or later. Nothing else: no build tool and no third-party libraries.

```
java -version   # must be 21+
```

## Run

```
./run.sh          # replay the event stream and print the per-day report
./run.sh test     # run the test suite
```

`run.sh` compiles everything into `out/` with `javac` and runs it. Without the script:

```
javac -d out $(find src -name '*.java')
java -cp out ledger.Replay
java -cp out ledger.LedgerTests
```

## Reading the replay output

One block per day, `DAY 1` to `DAY 6`, printed at the close of that day:

```
Events:                      every event processed today, in replay order, with its outcome
Fee assessments:             overdraft fees booked by today's end-of-day run
Interest capitalisation:     Day 6 only: the closing balance and rounded accrual for each day
Errors:                      rejected events and end-of-day errors, with the reason
Authorizations:              every authorization's current state (APPROVED / DECLINED / SETTLED)
Balances at close of day N:  per account:
    closing ledger   closing ledger balance of day N (all entries with value_date ≤ N)
    current ledger   every entry booked so far, regardless of value date
    holds            active authorization holds
    available        current ledger − holds
    closing by value day   each day's closing balance as known now; * = changed since last report
```

Markers in event lines:

- `[BACK-VALUED to day X, booked day Y]`: the entry belongs to an earlier day's balance (E7, E9, E10).
- `[LATE: …]`: the event is stamped with a day that has already closed (E10). See AMBIGUITIES.md A2.

What to look for:

- **Day 5:** E7 lands back-valued to Day 2. Days 2–5 are restated (`*`). Auth-B is declined. Three
  fees are booked for Days 2, 4 and 5. Day 3 survives at +5.00.
- **Day 6:** E9 reverses E7 but the fees stay. Interest is capitalised on the restated balances:
  ACC-001 ends at 390.93, ACC-002 at 10.008.

## Reading the test output

Each line is `PASS` or `FAIL` plus the test name. Tests are named after the acceptance criterion they
prove, or, for refused criteria, the behaviour that replaces it.

Exactly one test fails, on purpose: **`reversal restores the customer to the pre-E7 position`**. It
is a test against this design, and its annotation in `src/test/java/ledger/LedgerTests.java`
explains what it reveals. The summary line counts it separately, and the process exits 0 unless some
*other* test fails:

```
17 passed, 1 failed (0 unexpected)
```

## Layout

```
src/main/java/ledger/
  LedgerEngine.java        single writer: applies events, end-of-day fees, interest capitalisation
  Ledger.java              append-only store, balance caches, folds, reconcile()
  Journal, Posting         a balanced double-entry transaction and its legs
  AuthRecord, AuthStatus   authorization lifecycle records (holds live here, not in the ledger)
  Event.java               the event types
  Rules.java               every business constant
  Currency.java            AED (2 dp), BHD (3 dp); refuses over-precise amounts
  Scenario.java            the accounts and event stream from the brief
  Replay.java              replays the stream, closes days, prints the report
src/test/java/ledger/
  LedgerTests.java         the suite, including the annotated failing test
  Check.java               minimal assertions
```

## Documents

| File | Contents |
|---|---|
| `DECISIONS.md` | architecture decisions: bitemporal model, double-entry, caching vs folding, concurrency, idempotency, audit |
| `NUMBERS.md` | every constant, why that value and not half of it, and a table of the resulting numbers |
| `AMBIGUITIES.md` | every ambiguity found and how it was resolved |
| `REJECTED.md` | refused acceptance criteria with reasons, and approaches abandoned mid-build |
| `WORKLOG.md` | timestamped work log |
