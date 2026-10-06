package ledger;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays an event stream in the given order and prints a report at the close of each day.
 *
 * <p>The processing clock only moves forward. When the next event is stamped with a later day,
 * every day up to it is closed first (fees, and on the last day interest). An event stamped with
 * a day that has already closed — E10 in the brief — is processed on the current day and flagged
 * as a late arrival; its value date still places it on the right day's balance.
 */
public final class Replay {

    private final LedgerEngine engine;
    private final List<Event> events;
    private final PrintStream out;
    private int cursor;
    private int currentDay = Rules.FIRST_DAY;
    private final List<String> dayLines = new ArrayList<>();
    private final Map<String, BigDecimal> lastReportedClosings = new HashMap<>();
    private final List<List<String>> reconciliationProblems = new ArrayList<>();

    public Replay(LedgerEngine engine, List<Event> events, PrintStream out) {
        this.engine = engine;
        this.events = List.copyOf(events);
        this.out = out;
    }

    public static void main(String[] args) {
        Replay replay = new Replay(Scenario.openAccounts(), Scenario.events(), System.out);
        replay.runToEnd();
    }

    public LedgerEngine engine() {
        return engine;
    }

    public int currentDay() {
        return currentDay;
    }

    /** Every reconciliation failure seen after any event or day close; empty when the books always agreed. */
    public List<List<String>> reconciliationProblems() {
        return reconciliationProblems;
    }

    /** Processes events up to and including {@code eventId}, closing any days passed on the way. */
    public void runThrough(String eventId) {
        while (cursor < events.size()) {
            Event event = events.get(cursor);
            step();
            if (event.id().equals(eventId)) {
                return;
            }
        }
        throw new IllegalArgumentException("No event " + eventId + " in the stream");
    }

    public void runToEnd() {
        while (cursor < events.size()) {
            step();
        }
        closeDaysThrough(Rules.LAST_DAY);
    }

    private void step() {
        Event event = events.get(cursor++);
        if (event.day() > currentDay) {
            closeDaysThrough(event.day() - 1);
            currentDay = event.day();
        }
        Outcome outcome = engine.apply(event, currentDay);
        String late = event.day() < currentDay
                ? " [LATE: stamped day " + event.day() + ", arrived after day " + currentDay + " events; processed on day " + currentDay + "]"
                : "";
        dayLines.add(String.format("  %-4s %-8s %s%s", event.id(), event.accountId(), outcome.summary(), late));
        reconcile();
    }

    private void closeDaysThrough(int lastDayToClose) {
        while (currentDay <= lastDayToClose) {
            List<Journal> fees = engine.assessOverdraftFees(currentDay);
            List<InterestCapitalisation> interest = currentDay == Rules.LAST_DAY
                    ? engine.capitaliseInterest(currentDay)
                    : List.of();
            reconcile();
            printDay(fees, interest);
            dayLines.clear();
            currentDay++;
        }
    }

    private void reconcile() {
        List<String> problems = engine.ledger().reconcile();
        if (!problems.isEmpty()) {
            reconciliationProblems.add(problems);
        }
    }

    // ---- report ----

    private void printDay(List<Journal> fees, List<InterestCapitalisation> interest) {
        Ledger ledger = engine.ledger();
        int day = currentDay;
        out.println("================================ DAY " + day + " ================================");

        out.println("Events:");
        printOrNone(dayLines);

        out.println("Fee assessments (end of day " + day + "):");
        List<String> feeLines = new ArrayList<>();
        for (Journal fee : fees) {
            Posting customerLeg = fee.postings().get(0);
            feeLines.add("  " + customerLeg.accountId() + " OVERDRAFT_FEE " + customerLeg.amount().negate()
                    + " value-dated day " + fee.valueDay() + " (" + fee.detail() + ")");
        }
        printOrNone(feeLines);

        if (!interest.isEmpty()) {
            out.println("Interest capitalisation (end of day " + day + "):");
            for (InterestCapitalisation result : interest) {
                out.println("  " + result.accountId() + " closings " + result.closingBalances());
                out.println("  " + " ".repeat(result.accountId().length()) + " accruals " + result.dailyAccruals()
                        + " -> capitalised " + result.total());
            }
        }

        out.println("Errors:");
        List<String> errorLines = new ArrayList<>();
        for (Rejection rejection : ledger.rejections()) {
            if (rejection.day() == day) {
                errorLines.add("  " + rejection.eventId() + " " + rejection.accountId() + ": " + rejection.reason());
            }
        }
        printOrNone(errorLines);

        out.println("Authorizations:");
        List<String> authLines = new ArrayList<>();
        for (Account account : ledger.customerAccounts()) {
            ledger.latestAuthStates(account.id()).values().forEach(auth -> authLines.add("  " + account.id() + " "
                    + auth.authId() + " " + auth.status() + " hold " + auth.holdAmount()
                    + (auth.settledAmount() != null ? ", settled " + auth.settledAmount() : "")));
        }
        printOrNone(authLines);

        out.println("Balances at close of day " + day + ":");
        for (Account account : ledger.customerAccounts()) {
            out.println("  " + account.id() + " " + account.currency()
                    + "  closing ledger " + ledger.closingBalance(account.id(), day)
                    + "  | current ledger " + ledger.ledgerBalance(account.id())
                    + "  holds " + ledger.activeHolds(account.id())
                    + "  available " + ledger.availableBalance(account.id()));
            StringBuilder restated = new StringBuilder("      closing by value day:");
            for (int d = Rules.FIRST_DAY; d <= day; d++) {
                BigDecimal closing = ledger.closingBalance(account.id(), d);
                BigDecimal previous = lastReportedClosings.put(account.id() + "#" + d, closing);
                boolean changed = previous != null && previous.compareTo(closing) != 0;
                restated.append("  D").append(d).append(' ').append(closing).append(changed ? "*" : "");
            }
            out.println(restated);
        }
        out.println("      (* = restated since previously reported, by a back-valued entry or fee)");
        out.println();
    }

    private void printOrNone(List<String> lines) {
        if (lines.isEmpty()) {
            out.println("  none");
        } else {
            lines.forEach(out::println);
        }
    }
}
