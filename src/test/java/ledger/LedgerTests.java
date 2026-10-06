package ledger;

import static ledger.Scenario.ACC_001;
import static ledger.Scenario.ACC_002;

import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test suite. Run with {@code ./run.sh test}. Tests named after an acceptance criterion
 * either prove it (accepted criteria) or prove the behaviour that replaces it (criteria refused in
 * REJECTED.md).
 *
 * <p>Exactly one test is expected to fail: {@link #reversalRestoresTheCustomerToThePreE7Position}.
 * It is a test against this design, kept on purpose and annotated inline. The process exits
 * non-zero only if any <em>other</em> test fails.
 */
public final class LedgerTests {

    private static final String KNOWN_FAILURE = "reversal restores the customer to the pre-E7 position";

    public static void main(String[] args) {
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("criterion 1: Day 2 closing is -370.00 at end of Day 5 before fees", LedgerTests::day2ClosingBeforeFees);
        tests.put("criterion 2 refused: E7 causes three fees (Days 2, 4, 5), Day 3 stays at +5.00", LedgerTests::backValuedDebitCausesThreeFees);
        tests.put("criterion 3: Auth-A settles for 185.00 and its 200.00 hold is released", LedgerTests::authASettlementAccepted);
        tests.put("criterion 4: settlement for unknown Auth-Z is rejected and moves no funds", LedgerTests::unknownAuthorizationSettlementRejected);
        tests.put("criterion 5 premise fails: Auth-B is declined, with its decision inputs recorded", LedgerTests::authBDeclined);
        tests.put("criterion 5 invariant: an approved hold reduces available, not ledger", LedgerTests::approvedHoldReducesAvailableOnly);
        tests.put("criterion 6 refused: fees stand after E9; balances do not return to pre-E7", LedgerTests::reversalDoesNotUndoFees);
        tests.put("criterion 7 refused: 10.000 BHD splits 3.333 + 3.333 + 3.334", LedgerTests::instalmentsSumExactly);
        tests.put("criterion 8 refused: capitalised interest is exactly the sum of rounded accruals", LedgerTests::interestSumsExactly);
        tests.put("final closing balances after interest capitalisation", LedgerTests::finalBalances);
        tests.put("append-only: E9 adds a journal and leaves E7's untouched", LedgerTests::appendOnly);
        tests.put("double-entry: every currency nets to zero and caches reconcile after every step", LedgerTests::booksBalanceAndReconcile);
        tests.put("idempotency: a duplicate event id is rejected and changes nothing", LedgerTests::duplicateEventIdRejected);
        tests.put("precision: an AED amount with three decimals is refused, not rounded", LedgerTests::overPreciseAmountRejected);
        tests.put("settlement above its hold is rejected", LedgerTests::settlementAboveHoldRejected);
        tests.put("a journal cannot be reversed twice", LedgerTests::doubleReversalRejected);
        tests.put("a negative BHD balance raises an error instead of an AED fee", LedgerTests::bhdOverdraftNeedsFxRate);
        tests.put(KNOWN_FAILURE, LedgerTests::reversalRestoresTheCustomerToThePreE7Position);

        int passed = 0;
        List<String> unexpectedFailures = new ArrayList<>();
        for (Map.Entry<String, Runnable> test : tests.entrySet()) {
            try {
                test.getValue().run();
                passed++;
                System.out.println("PASS  " + test.getKey());
            } catch (Throwable failure) {
                boolean known = test.getKey().equals(KNOWN_FAILURE);
                System.out.println((known ? "FAIL  (intentional, see annotation) " : "FAIL  ") + test.getKey());
                System.out.println("      " + failure.getMessage());
                if (!known) {
                    unexpectedFailures.add(test.getKey());
                }
            }
        }
        System.out.println();
        System.out.println(passed + " passed, " + (tests.size() - passed) + " failed ("
                + unexpectedFailures.size() + " unexpected)");
        if (!unexpectedFailures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---- helpers ----

    private static final PrintStream SILENT = new PrintStream(OutputStream.nullOutputStream());

    private static Replay fullReplay() {
        Replay replay = new Replay(Scenario.openAccounts(), Scenario.events(), SILENT);
        replay.runToEnd();
        return replay;
    }

    private static Replay replayThrough(String eventId) {
        Replay replay = new Replay(Scenario.openAccounts(), Scenario.events(), SILENT);
        replay.runThrough(eventId);
        return replay;
    }

    private static List<Journal> fees(Ledger ledger) {
        return ledger.journals().stream().filter(j -> j.type() == JournalType.OVERDRAFT_FEE).toList();
    }

    private static BigDecimal aed(String amount) {
        return new BigDecimal(amount);
    }

    // ---- acceptance criteria ----

    private static void day2ClosingBeforeFees() {
        Replay replay = replayThrough("E8");
        Ledger ledger = replay.engine().ledger();
        Check.equal(5, replay.currentDay(), "still on day 5, before its end-of-day run");
        Check.equal(0, fees(ledger).size(), "no fee assessed yet");
        Check.amount("-370.00", ledger.closingBalance(ACC_001, 2), "Day 2 closing ledger balance");
    }

    private static void backValuedDebitCausesThreeFees() {
        Ledger before = replayThrough("E6").engine().ledger();
        for (int day = 1; day <= 4; day++) {
            Check.isTrue(before.closingBalance(ACC_001, day).signum() >= 0, "before E7, Day " + day + " is not negative");
        }

        Replay replay = new Replay(Scenario.openAccounts(), Scenario.events(), SILENT);
        replay.runThroughEndOfDay(5);
        Ledger ledger = replay.engine().ledger();
        List<Journal> fees = fees(ledger);
        Check.equal(List.of(2, 4, 5), fees.stream().map(Journal::valueDay).toList(), "fee value days");
        Check.isTrue(fees.stream().allMatch(f -> f.bookedDay() == 5), "all three booked at end of Day 5");
        // Day 3 escapes a fee by 5.00: -370.00 - 25.00 (Day 2 fee) + 400.00. See NUMBERS.md.
        Check.amount("5.00", ledger.closingBalance(ACC_001, 3), "Day 3 closing at end of Day 5");
        Check.amount("-395.00", ledger.closingBalance(ACC_001, 2), "Day 2 closing after its fee");
        Check.amount("-230.00", ledger.closingBalance(ACC_001, 5), "Day 5 closing after three fees");

        Check.equal(3, fees(fullReplay().engine().ledger()).size(), "no further fee on Day 6");
    }

    private static void authASettlementAccepted() {
        Ledger ledger = replayThrough("E5").engine().ledger();
        AuthRecord authA = ledger.latestAuthState(ACC_001, "Auth-A").orElseThrow();
        Check.equal(AuthStatus.SETTLED, authA.status(), "Auth-A status");
        Check.amount("185.00", authA.settledAmount(), "settled amount");
        Check.amount("0.00", ledger.activeHolds(ACC_001), "holds after settlement (15.00 unused hold released)");
        Check.amount("465.00", ledger.ledgerBalance(ACC_001), "ledger after settlement");
    }

    private static void unknownAuthorizationSettlementRejected() {
        Ledger ledger = replayThrough("E6").engine().ledger();
        Check.equal(0, ledger.journalsFromEvent("E6").size(), "no journal for E6");
        Check.isTrue(ledger.rejections().stream().anyMatch(r -> r.eventId().equals("E6")), "E6 recorded as an error");
        Check.amount("465.00", ledger.ledgerBalance(ACC_001), "ledger unchanged by E6");
    }

    private static void authBDeclined() {
        Ledger ledger = replayThrough("E8").engine().ledger();
        AuthRecord authB = ledger.latestAuthState(ACC_001, "Auth-B").orElseThrow();
        Check.equal(AuthStatus.DECLINED, authB.status(), "Auth-B status");
        Check.amount("-155.00", authB.ledgerBalance(), "ledger balance the decision saw");
        Check.amount("0.00", authB.activeHolds(), "holds the decision saw");
        Check.amount("-245.00", authB.availableAfter(), "available had the hold been placed");
        Check.amount("0.00", ledger.activeHolds(ACC_001), "a declined auth holds nothing");
    }

    private static void approvedHoldReducesAvailableOnly() {
        LedgerEngine engine = new LedgerEngine();
        engine.openCustomerAccount("T-1", Currency.AED, aed("0.00"));
        engine.apply(new Event.Credit("T1", 1, "T-1", aed("100.00"), 1), 1);
        engine.apply(new Event.Authorization("T2", 1, "T-1", "Auth-T", aed("90.00"), 1), 1);
        Ledger ledger = engine.ledger();
        Check.equal(AuthStatus.APPROVED, ledger.latestAuthState("T-1", "Auth-T").orElseThrow().status(), "approved");
        Check.amount("100.00", ledger.ledgerBalance("T-1"), "ledger balance unchanged by the hold");
        Check.amount("100.00", ledger.closingBalance("T-1", 1), "closing balance unchanged by the hold");
        Check.amount("10.00", ledger.availableBalance("T-1"), "available reduced by the hold");
    }

    private static void reversalDoesNotUndoFees() {
        Ledger ledger = fullReplay().engine().ledger();
        Check.equal(3, fees(ledger).size(), "fees remain after E9");
        Check.amount("225.00", ledger.closingBalance(ACC_001, 2), "Day 2 after E9 (pre-E7 it was 250.00)");
        Check.amount("415.00", ledger.closingBalance(ACC_001, 4), "Day 4 after E9 (pre-E7 it was 465.00)");
        Check.equal(AuthStatus.DECLINED, ledger.latestAuthState(ACC_001, "Auth-B").orElseThrow().status(),
                "Auth-B decline is not undone");
    }

    private static void instalmentsSumExactly() {
        Journal journal = fullReplay().engine().ledger().journalsFromEvent("E10").get(0);
        List<String> parts = journal.postings().stream()
                .filter(p -> p.accountId().equals(ACC_002))
                .map(p -> p.amount().toPlainString())
                .toList();
        Check.equal(List.of("3.333", "3.333", "3.334"), parts, "instalments");
        Check.amount("10.000", journal.netFor(ACC_002, Currency.BHD.zero()), "instalments sum");
    }

    private static void interestSumsExactly() {
        Ledger ledger = fullReplay().engine().ledger();
        Journal acc1 = ledger.journalsFromEvent("EOD6-INTEREST-" + ACC_001).get(0);
        Journal acc2 = ledger.journalsFromEvent("EOD6-INTEREST-" + ACC_002).get(0);
        Check.amount("0.93", acc1.netFor(ACC_001, Currency.AED.zero()), "ACC-001 capitalised");
        Check.amount("0.008", acc2.netFor(ACC_002, Currency.BHD.zero()), "ACC-002 capitalised");
        Check.equal(6, acc1.valueDay(), "capitalised at end of Day 6");

        // Recompute independently from the restated closings: 250, 225, 625, 415, 390, 390.
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        for (String closing : List.of("250.00", "225.00", "625.00", "415.00", "390.00", "390.00")) {
            sum = sum.add(new BigDecimal(closing).multiply(Rules.DAILY_INTEREST_RATE).setScale(2, Rules.INTEREST_ROUNDING));
        }
        Check.amount("0.93", sum, "sum of independently rounded accruals");
    }

    private static void finalBalances() {
        Ledger ledger = fullReplay().engine().ledger();
        Check.amount("390.93", ledger.closingBalance(ACC_001, 6), "ACC-001 Day 6 closing");
        Check.amount("390.93", ledger.availableBalance(ACC_001), "ACC-001 available");
        Check.amount("10.008", ledger.closingBalance(ACC_002, 6), "ACC-002 Day 6 closing");
    }

    // ---- design properties ----

    private static void appendOnly() {
        Ledger ledger = replayThrough("E8").engine().ledger();
        Journal e7Before = ledger.journalsFromEvent("E7").get(0);
        int countBefore = ledger.journals().size();

        Ledger after = fullReplay().engine().ledger();
        Journal e7After = after.journalsFromEvent("E7").get(0);
        Check.equal(e7Before, e7After, "E7 journal identical after E9");
        Check.isTrue(after.journals().size() > countBefore, "E9 appended rather than replaced");
        Check.equal(JournalType.REVERSAL, after.journalsFromEvent("E9").get(0).type(), "E9 is a REVERSAL journal");
        Check.throwsException(UnsupportedOperationException.class, () -> after.journals().clear(),
                "journal view is read-only");
    }

    private static void booksBalanceAndReconcile() {
        Replay replay = fullReplay();
        Ledger ledger = replay.engine().ledger();
        Check.equal(List.of(), replay.reconciliationProblems(), "no reconciliation problems at any step");
        Check.equal(List.of(), ledger.reconcile(), "final reconciliation");
        Check.amount("75.00", ledger.ledgerBalance(InternalAccount.FEE_INCOME.idFor(Currency.AED)), "fee income");
        Check.amount("-0.93", ledger.ledgerBalance(InternalAccount.INTEREST_EXPENSE.idFor(Currency.AED)), "AED interest expense");
        Check.amount("185.00", ledger.ledgerBalance(InternalAccount.CARD_SCHEME_SETTLEMENT.idFor(Currency.AED)), "owed to scheme");
    }

    private static void duplicateEventIdRejected() {
        LedgerEngine engine = Scenario.openAccounts();
        Event credit = new Event.Credit("X1", 1, ACC_001, aed("10.00"), 1);
        Check.isTrue(engine.apply(credit, 1).accepted(), "first delivery accepted");
        Check.isTrue(!engine.apply(credit, 1).accepted(), "second delivery rejected");
        Check.amount("10.00", engine.ledger().ledgerBalance(ACC_001), "credited once");
    }

    private static void overPreciseAmountRejected() {
        LedgerEngine engine = Scenario.openAccounts();
        Outcome outcome = engine.apply(new Event.Credit("X1", 1, ACC_001, aed("1.234"), 1), 1);
        Check.isTrue(!outcome.accepted(), "rejected");
        Check.equal(0, engine.ledger().journalsFromEvent("X1").size(), "nothing posted");
    }

    private static void settlementAboveHoldRejected() {
        LedgerEngine engine = Scenario.openAccounts();
        engine.apply(new Event.Credit("X1", 1, ACC_001, aed("500.00"), 1), 1);
        engine.apply(new Event.Authorization("X2", 1, ACC_001, "Auth-X", aed("100.00"), 1), 1);
        Outcome outcome = engine.apply(new Event.Settlement("X3", 1, ACC_001, "Auth-X", aed("100.01"), 1), 1);
        Check.isTrue(!outcome.accepted(), "over-capture rejected");
        Check.amount("100.00", engine.ledger().activeHolds(ACC_001), "hold still in place");
    }

    private static void doubleReversalRejected() {
        LedgerEngine engine = Scenario.openAccounts();
        engine.apply(new Event.Debit("X1", 1, ACC_001, aed("50.00"), 1), 1);
        Check.isTrue(engine.apply(new Event.Reversal("X2", 1, ACC_001, "X1", 1), 1).accepted(), "first reversal");
        Check.isTrue(!engine.apply(new Event.Reversal("X3", 1, ACC_001, "X1", 1), 1).accepted(), "second reversal");
        Check.amount("0.00", engine.ledger().ledgerBalance(ACC_001), "net zero, not +50.00");
    }

    private static void bhdOverdraftNeedsFxRate() {
        LedgerEngine engine = Scenario.openAccounts();
        engine.apply(new Event.Debit("X1", 1, ACC_002, new BigDecimal("1.000"), 1), 1);
        Check.equal(List.of(), engine.assessOverdraftFees(1), "no fee journal");
        Check.isTrue(engine.ledger().rejections().stream().anyMatch(r -> r.accountId().equals(ACC_002)),
                "error recorded for the unassessable fee");
    }

    // ---- the failing test ----

    /**
     * INTENTIONALLY FAILING — a test against this design.
     *
     * <p>Claim tested: E9 reverses E7, so the customer should end up exactly where they would have
     * been had E7 never happened. The counterfactual is computed by replaying the same stream with
     * E7 and E9 removed.
     *
     * <p>What the failure reveals:
     * <ol>
     *   <li><b>Fees are path-dependent.</b> The three fees (75.00) were assessed on a balance the
     *       ledger later learned was wrong. The design treats a fee as a decision made on the facts
     *       booked at the time and never revisits it (DECISIONS.md D9), so the reversal restores the
     *       money movement but not its consequences.</li>
     *   <li><b>Authorization decisions are path-dependent.</b> Without E7, Auth-B is approved
     *       (465.00 - 90.00 = 375.00 available). With E7 present for one day, it is declined — a real
     *       customer's card was refused because of a debit that was later reversed. No ledger
     *       mechanism can undo a decline after the fact.</li>
     *   <li><b>Interest inherits the error.</b> Because the fees stay in the restated closings,
     *       the capitalised interest is 0.93 instead of 1.03.</li>
     * </ol>
     * The expected values below are the counterfactual; the actual values are 390.93 and DECLINED.
     * Fixing (1) needs a fee-waiver policy tied to reversals; (2) cannot be fixed in the ledger at
     * all. This is why criterion 6 is refused rather than implemented.
     */
    private static void reversalRestoresTheCustomerToThePreE7Position() {
        List<Event> withoutE7 = Scenario.events().stream()
                .filter(e -> !e.id().equals("E7") && !e.id().equals("E9"))
                .toList();
        Replay counterfactual = new Replay(Scenario.openAccounts(), withoutE7, SILENT);
        counterfactual.runToEnd();
        Ledger expected = counterfactual.engine().ledger();
        Ledger actual = fullReplay().engine().ledger();

        Check.amount(expected.closingBalance(ACC_001, 6).toPlainString(), actual.closingBalance(ACC_001, 6),
                "ACC-001 Day 6 closing vs a world without E7 (466.03 = 465.00 + 1.03 interest)");
        Check.equal(expected.latestAuthState(ACC_001, "Auth-B").orElseThrow().status(),
                actual.latestAuthState(ACC_001, "Auth-B").orElseThrow().status(),
                "Auth-B status vs a world without E7");
    }
}
