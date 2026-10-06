package ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies events and end-of-day processing to a {@link Ledger}. This is the ledger's single
 * writer: every public mutating method is {@code synchronized}, so an event's validation, decision
 * and append happen as one atomic step (DECISIONS.md D5). Each event is all-or-nothing: it is
 * fully validated before anything is appended (D6).
 */
public final class LedgerEngine {

    private final Ledger ledger = new Ledger();
    private final Set<String> seenEventIds = new HashSet<>();

    public LedgerEngine() {
        for (Currency currency : Currency.values()) {
            for (InternalAccount internal : InternalAccount.values()) {
                ledger.openAccount(new Account(internal.idFor(currency), currency, Account.AccountKind.INTERNAL));
            }
        }
    }

    public Ledger ledger() {
        return ledger;
    }

    /**
     * Opens a customer account. A non-zero opening balance is booked as an ordinary credit journal
     * against external clearing, value-dated the day before the window opens, so that the opening
     * balance is itself an auditable record rather than a hidden starting value.
     */
    public synchronized void openCustomerAccount(String accountId, Currency currency, BigDecimal openingBalance) {
        ledger.openAccount(new Account(accountId, currency, Account.AccountKind.CUSTOMER));
        BigDecimal opening = currency.normalise(openingBalance);
        if (opening.signum() != 0) {
            int openingDay = Rules.FIRST_DAY - 1;
            ledger.appendJournal(JournalType.CREDIT, openingDay, openingDay, "OPENING-" + accountId, null,
                    "opening balance", List.of(
                            new Posting(accountId, opening),
                            new Posting(InternalAccount.EXTERNAL_CLEARING.idFor(currency), opening.negate())));
        }
    }

    // ---- events ----

    /** Applies one event on {@code processingDay}. Never throws for a bad event: it is recorded as a rejection. */
    public synchronized Outcome apply(Event event, int processingDay) {
        if (!seenEventIds.add(event.id())) {
            return Outcome.rejected(ledger.reject(processingDay, event.id(), event.accountId(),
                    "duplicate event id " + event.id() + "; already processed, nothing changed"));
        }
        try {
            return switch (event) {
                case Event.Credit e -> credit(e, processingDay);
                case Event.Debit e -> debit(e, processingDay);
                case Event.Authorization e -> authorize(e, processingDay);
                case Event.Settlement e -> settle(e, processingDay);
                case Event.Reversal e -> reverse(e, processingDay);
                case Event.InstalmentCredit e -> creditInInstalments(e, processingDay);
            };
        } catch (Refused | IllegalArgumentException refusal) {
            return Outcome.rejected(ledger.reject(processingDay, event.id(), event.accountId(), refusal.getMessage()));
        }
    }

    private Outcome credit(Event.Credit e, int processingDay) {
        Currency currency = customerCurrency(e.accountId());
        BigDecimal amount = positiveAmount(currency, e.amount());
        checkValueDay(e.valueDay(), processingDay);
        Journal journal = ledger.appendJournal(JournalType.CREDIT, e.valueDay(), processingDay, e.id(), null, null, List.of(
                new Posting(e.accountId(), amount),
                new Posting(InternalAccount.EXTERNAL_CLEARING.idFor(currency), amount.negate())));
        return Outcome.accepted(e.id(), "CREDIT " + amount + " " + currency + valueDayNote(journal));
    }

    /** Posted debits are booked facts and are not checked against available balance; only authorizations are gated. */
    private Outcome debit(Event.Debit e, int processingDay) {
        Currency currency = customerCurrency(e.accountId());
        BigDecimal amount = positiveAmount(currency, e.amount());
        checkValueDay(e.valueDay(), processingDay);
        Journal journal = ledger.appendJournal(JournalType.DEBIT, e.valueDay(), processingDay, e.id(), null, null, List.of(
                new Posting(e.accountId(), amount.negate()),
                new Posting(InternalAccount.EXTERNAL_CLEARING.idFor(currency), amount)));
        return Outcome.accepted(e.id(), "DEBIT " + amount + " " + currency + valueDayNote(journal));
    }

    private Outcome authorize(Event.Authorization e, int processingDay) {
        Currency currency = customerCurrency(e.accountId());
        BigDecimal amount = positiveAmount(currency, e.amount());
        checkValueDay(e.valueDay(), processingDay);
        if (ledger.latestAuthState(e.accountId(), e.authId()).isPresent()) {
            throw new Refused("authorization id " + e.authId() + " already exists on " + e.accountId());
        }
        BigDecimal availableAfter = ledger.availableBalance(e.accountId()).subtract(amount);
        AuthStatus status = availableAfter.signum() >= 0 ? AuthStatus.APPROVED : AuthStatus.DECLINED;
        AuthRecord record = ledger.appendAuth(e.accountId(), e.authId(), status, amount, null, processingDay, e.id());
        return Outcome.accepted(e.id(), "AUTHORIZATION " + e.authId() + " hold " + amount + " " + currency
                + " -> " + status + " (ledger " + record.ledgerBalance() + ", holds " + record.activeHolds()
                + ", available after " + record.availableAfter() + ")");
    }

    private Outcome settle(Event.Settlement e, int processingDay) {
        Currency currency = customerCurrency(e.accountId());
        BigDecimal amount = positiveAmount(currency, e.amount());
        checkValueDay(e.valueDay(), processingDay);
        AuthRecord auth = ledger.latestAuthState(e.accountId(), e.authId()).orElseThrow(() -> new Refused(
                "settlement references " + e.authId() + ", which has no authorization on " + e.accountId()
                        + "; no funds moved"));
        if (auth.status() != AuthStatus.APPROVED) {
            throw new Refused("settlement references " + e.authId() + ", which is " + auth.status()
                    + "; no funds moved");
        }
        BigDecimal ceiling = auth.holdAmount().add(auth.holdAmount().multiply(Rules.SETTLEMENT_OVER_AUTH_TOLERANCE));
        if (amount.compareTo(ceiling) > 0) {
            throw new Refused("settlement " + amount + " exceeds " + e.authId() + " hold " + auth.holdAmount()
                    + "; no funds moved");
        }
        Journal journal = ledger.appendJournal(JournalType.SETTLEMENT, e.valueDay(), processingDay, e.id(), e.authId(),
                "hold " + auth.holdAmount() + " released, " + amount + " settled", List.of(
                        new Posting(e.accountId(), amount.negate()),
                        new Posting(InternalAccount.CARD_SCHEME_SETTLEMENT.idFor(currency), amount)));
        ledger.appendAuth(e.accountId(), e.authId(), AuthStatus.SETTLED, auth.holdAmount(), amount, processingDay, e.id());
        return Outcome.accepted(e.id(), "SETTLEMENT " + e.authId() + " " + amount + " " + currency + " against hold "
                + auth.holdAmount() + "; hold released" + valueDayNote(journal));
    }

    /** Reverses the money movement of an earlier credit or debit by appending its mirror image. */
    private Outcome reverse(Event.Reversal e, int processingDay) {
        customerCurrency(e.accountId());
        checkValueDay(e.valueDay(), processingDay);
        List<Journal> targets = ledger.journalsFromEvent(e.reversedEventId()).stream()
                .filter(j -> j.touches(e.accountId()))
                .toList();
        if (targets.isEmpty()) {
            throw new Refused("nothing to reverse: event " + e.reversedEventId() + " has no journal on " + e.accountId());
        }
        Journal original = targets.get(0);
        if (original.type() != JournalType.CREDIT && original.type() != JournalType.DEBIT) {
            throw new Refused("only credits and debits can be reversed; " + e.reversedEventId() + " is " + original.type());
        }
        if (ledger.isReversed(original)) {
            throw new Refused(e.reversedEventId() + " is already reversed");
        }
        List<Posting> mirror = original.postings().stream()
                .map(p -> new Posting(p.accountId(), p.amount().negate()))
                .toList();
        Journal journal = ledger.appendJournal(JournalType.REVERSAL, e.valueDay(), processingDay, e.id(),
                Ledger.reference(original), "reverses " + e.reversedEventId(), mirror);
        return Outcome.accepted(e.id(), "REVERSAL of " + e.reversedEventId() + " (" + original.type() + " "
                + original.netFor(e.accountId(), BigDecimal.ZERO).abs() + "), original journal untouched"
                + valueDayNote(journal));
    }

    /**
     * Splits {@code total} into {@code parts} postings at the currency's precision: every part but
     * the last is the total divided and rounded down; the last absorbs the remainder, so the parts
     * always sum exactly to the total. All parts are legs of one journal, so they land together.
     */
    private Outcome creditInInstalments(Event.InstalmentCredit e, int processingDay) {
        Currency currency = customerCurrency(e.accountId());
        BigDecimal total = positiveAmount(currency, e.total());
        checkValueDay(e.valueDay(), processingDay);
        if (e.parts() < 1) {
            throw new Refused("instalment count must be at least 1");
        }
        BigDecimal part = total.divide(BigDecimal.valueOf(e.parts()), currency.scale(), RoundingMode.DOWN);
        if (part.signum() == 0) {
            throw new Refused(total + " cannot be split into " + e.parts() + " non-zero instalments");
        }
        BigDecimal last = total.subtract(part.multiply(BigDecimal.valueOf(e.parts() - 1)));
        List<Posting> legs = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (int i = 1; i <= e.parts(); i++) {
            BigDecimal amount = i == e.parts() ? last : part;
            legs.add(new Posting(e.accountId(), amount));
            shown.add(amount.toPlainString());
        }
        legs.add(new Posting(InternalAccount.EXTERNAL_CLEARING.idFor(currency), total.negate()));
        String split = String.join(" + ", shown);
        Journal journal = ledger.appendJournal(JournalType.CREDIT, e.valueDay(), processingDay, e.id(), null,
                e.parts() + " instalments: " + split, legs);
        return Outcome.accepted(e.id(), "CREDIT " + total + " " + currency + " in " + e.parts() + " instalments ("
                + split + ")" + valueDayNote(journal));
    }

    // ---- end of day ----

    /**
     * Assesses overdraft fees at the end of {@code processingDay}. Every day up to and including it
     * is checked, in order, because a back-valued journal can make an earlier day's closing balance
     * negative after that day has ended. At most one fee per account per value day; each fee is
     * value-dated to the day it is for, so it is included when later days are checked.
     */
    public synchronized List<Journal> assessOverdraftFees(int processingDay) {
        List<Journal> fees = new ArrayList<>();
        for (Account account : ledger.customerAccounts()) {
            for (int day = Rules.FIRST_DAY; day <= processingDay; day++) {
                BigDecimal closing = ledger.closingBalance(account.id(), day);
                if (closing.signum() >= 0 || ledger.hasJournal(account.id(), JournalType.OVERDRAFT_FEE, day)) {
                    continue;
                }
                String eventId = "EOD" + processingDay + "-FEE-" + account.id() + "-D" + day;
                if (account.currency() != Rules.OVERDRAFT_FEE_CURRENCY) {
                    boolean alreadyReported = ledger.rejections().stream().anyMatch(r -> r.eventId().equals(eventId));
                    if (!alreadyReported) {
                        ledger.reject(processingDay, eventId, account.id(), "closing balance " + closing + " on day " + day
                                + " is negative but the " + Rules.OVERDRAFT_FEE_CURRENCY + " fee cannot be booked to a "
                                + account.currency() + " account without an FX rate");
                    }
                    continue;
                }
                fees.add(ledger.appendJournal(JournalType.OVERDRAFT_FEE, day, processingDay, eventId, "fee-day-" + day,
                        "closing balance on day " + day + " was " + closing, List.of(
                                new Posting(account.id(), Rules.OVERDRAFT_FEE.negate()),
                                new Posting(InternalAccount.FEE_INCOME.idFor(account.currency()), Rules.OVERDRAFT_FEE))));
            }
        }
        return fees;
    }

    /**
     * Capitalises interest for every customer account as a single credit value-dated {@code day}.
     * Each day's accrual is computed on that day's closing balance as restated by every journal
     * booked so far, positive balances only, rounded to the currency's precision. The capitalised
     * amount is the sum of the rounded accruals, so the two agree by construction.
     */
    public synchronized List<InterestCapitalisation> capitaliseInterest(int day) {
        List<InterestCapitalisation> results = new ArrayList<>();
        for (Account account : ledger.customerAccounts()) {
            if (ledger.hasJournal(account.id(), JournalType.INTEREST, day)) {
                throw new IllegalStateException("Interest already capitalised for " + account.id() + " on day " + day);
            }
            Currency currency = account.currency();
            List<BigDecimal> closings = new ArrayList<>();
            List<BigDecimal> accruals = new ArrayList<>();
            BigDecimal total = currency.zero();
            for (int d = Rules.FIRST_DAY; d <= day; d++) {
                BigDecimal closing = ledger.closingBalance(account.id(), d);
                BigDecimal accrual = closing.signum() > 0
                        ? closing.multiply(Rules.DAILY_INTEREST_RATE).setScale(currency.scale(), Rules.INTEREST_ROUNDING)
                        : currency.zero();
                closings.add(closing);
                accruals.add(accrual);
                total = total.add(accrual);
            }
            Journal journal = null;
            if (total.signum() > 0) {
                journal = ledger.appendJournal(JournalType.INTEREST, day, day, "EOD" + day + "-INTEREST-" + account.id(),
                        null, "daily accruals " + accruals + " on closings " + closings, List.of(
                                new Posting(account.id(), total),
                                new Posting(InternalAccount.INTEREST_EXPENSE.idFor(currency), total.negate())));
            }
            results.add(new InterestCapitalisation(account.id(), List.copyOf(closings), List.copyOf(accruals), total, journal));
        }
        return results;
    }

    // ---- validation helpers ----

    private Currency customerCurrency(String accountId) {
        if (!ledger.hasAccount(accountId)) {
            throw new Refused("unknown account " + accountId);
        }
        Account account = ledger.account(accountId);
        if (account.kind() != Account.AccountKind.CUSTOMER) {
            throw new Refused(accountId + " is an internal account and cannot be targeted by an event");
        }
        return account.currency();
    }

    private static BigDecimal positiveAmount(Currency currency, BigDecimal amount) {
        BigDecimal normalised = currency.normalise(amount);
        if (normalised.signum() <= 0) {
            throw new Refused("amount must be positive, was " + amount);
        }
        return normalised;
    }

    private static void checkValueDay(int valueDay, int processingDay) {
        if (valueDay < Rules.FIRST_DAY || valueDay > Rules.LAST_DAY) {
            throw new Refused("value day " + valueDay + " is outside the window");
        }
        if (valueDay > processingDay) {
            throw new Refused("value day " + valueDay + " is after processing day " + processingDay
                    + "; forward-dated entries are not supported");
        }
    }

    private static String valueDayNote(Journal journal) {
        return journal.valueDay() < journal.bookedDay()
                ? " [BACK-VALUED to day " + journal.valueDay() + ", booked day " + journal.bookedDay() + "]"
                : "";
    }

    /** A business-rule refusal; becomes a {@link Rejection}, never escapes {@link #apply}. */
    private static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }
}
