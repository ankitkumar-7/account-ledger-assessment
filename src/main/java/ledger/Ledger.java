package ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only store of journals, authorization records and rejections. There is no update or
 * delete operation: a correction is always a new record (e.g. a REVERSAL journal).
 *
 * <p>Two kinds of balance (DECISIONS.md D4):
 * <ul>
 *   <li>Current ledger balance and active holds are kept in a write-through cache, updated inside
 *       the same append that writes the record. Authorization decisions read these.</li>
 *   <li>Value-dated daily closing balances are folded from the journals on every call, because
 *       back-valued journals rewrite them.</li>
 * </ul>
 * {@link #reconcile()} recomputes every cached value from the records and reports drift.
 */
public final class Ledger {

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Journal> journals = new ArrayList<>();
    private final List<AuthRecord> authRecords = new ArrayList<>();
    private final List<Rejection> rejections = new ArrayList<>();
    private long sequence;

    private final Map<String, BigDecimal> ledgerBalanceCache = new HashMap<>();
    private final Map<String, BigDecimal> activeHoldsCache = new HashMap<>();

    void openAccount(Account account) {
        if (accounts.putIfAbsent(account.id(), account) != null) {
            throw new IllegalArgumentException("Account already exists: " + account.id());
        }
        ledgerBalanceCache.put(account.id(), account.currency().zero());
        activeHoldsCache.put(account.id(), account.currency().zero());
    }

    public Account account(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Unknown account: " + accountId);
        }
        return account;
    }

    public boolean hasAccount(String accountId) {
        return accounts.containsKey(accountId);
    }

    public List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    public List<Account> customerAccounts() {
        return accounts.values().stream().filter(a -> a.kind() == Account.AccountKind.CUSTOMER).toList();
    }

    // ---- writes (package-private: only the engine appends) ----

    /**
     * Appends a journal after checking that every leg is at its account's currency precision and
     * that the legs sum to exactly zero in each currency. Nothing is written if a check fails.
     */
    Journal appendJournal(JournalType type, int valueDay, int bookedDay, String sourceEventId,
                          String reference, String detail, List<Posting> legs) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two postings");
        }
        List<Posting> normalised = new ArrayList<>();
        Map<Currency, BigDecimal> netByCurrency = new EnumMap<>(Currency.class);
        for (Posting leg : legs) {
            Currency currency = account(leg.accountId()).currency();
            BigDecimal amount = currency.normalise(leg.amount());
            normalised.add(new Posting(leg.accountId(), amount));
            netByCurrency.merge(currency, amount, BigDecimal::add);
        }
        netByCurrency.forEach((currency, net) -> {
            if (net.signum() != 0) {
                throw new IllegalArgumentException("Journal does not balance in " + currency + ": net " + net);
            }
        });

        Journal journal = new Journal(++sequence, type, valueDay, bookedDay, sourceEventId, reference, detail, normalised);
        journals.add(journal);
        for (Posting posting : normalised) {
            ledgerBalanceCache.merge(posting.accountId(), posting.amount(), BigDecimal::add);
        }
        return journal;
    }

    AuthRecord appendAuth(String accountId, String authId, AuthStatus status, BigDecimal holdAmount,
                          BigDecimal settledAmount, int day, String sourceEventId) {
        Currency currency = account(accountId).currency();
        BigDecimal hold = currency.normalise(holdAmount);
        BigDecimal settled = settledAmount == null ? null : currency.normalise(settledAmount);
        BigDecimal ledgerBalance = ledgerBalance(accountId);
        BigDecimal holdsBefore = activeHolds(accountId);

        BigDecimal holdsAfter = switch (status) {
            case APPROVED -> holdsBefore.add(hold);
            case SETTLED -> holdsBefore.subtract(hold);
            case DECLINED -> holdsBefore;
        };
        // For a decline, record the figure the decision refused: available had the hold been placed.
        BigDecimal availableAfter = status == AuthStatus.DECLINED
                ? ledgerBalance.subtract(holdsBefore.add(hold))
                : ledgerBalance.subtract(holdsAfter);
        AuthRecord record = new AuthRecord(++sequence, accountId, authId, status, hold, settled, day,
                sourceEventId, ledgerBalance, holdsBefore, availableAfter);
        authRecords.add(record);
        activeHoldsCache.put(accountId, holdsAfter);
        return record;
    }

    Rejection reject(int day, String eventId, String accountId, String reason) {
        Rejection rejection = new Rejection(day, eventId, accountId, reason);
        rejections.add(rejection);
        return rejection;
    }

    // ---- read-only views ----

    public List<Journal> journals() {
        return Collections.unmodifiableList(journals);
    }

    public List<AuthRecord> authRecords() {
        return Collections.unmodifiableList(authRecords);
    }

    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }

    public List<Journal> journalsFromEvent(String sourceEventId) {
        return journals.stream().filter(j -> j.sourceEventId().equals(sourceEventId)).toList();
    }

    public boolean hasJournal(String accountId, JournalType type, int valueDay) {
        return journals.stream().anyMatch(j ->
                j.type() == type && j.valueDay() == valueDay && j.touches(accountId));
    }

    public boolean isReversed(Journal original) {
        String ref = reference(original);
        return journals.stream().anyMatch(j -> j.type() == JournalType.REVERSAL && ref.equals(j.reference()));
    }

    static String reference(Journal journal) {
        return "J" + journal.sequence();
    }

    // ---- balances ----

    /** Current ledger balance (cached): every journal booked so far, regardless of value date. */
    public BigDecimal ledgerBalance(String accountId) {
        account(accountId);
        return ledgerBalanceCache.get(accountId);
    }

    /** Sum of holds on APPROVED authorizations (cached). */
    public BigDecimal activeHolds(String accountId) {
        account(accountId);
        return activeHoldsCache.get(accountId);
    }

    public BigDecimal availableBalance(String accountId) {
        return ledgerBalance(accountId).subtract(activeHolds(accountId));
    }

    /** Closing ledger balance of {@code valueDay}: every journal with value_date ≤ that day (folded). */
    public BigDecimal closingBalance(String accountId, int valueDay) {
        BigDecimal zero = account(accountId).currency().zero();
        BigDecimal balance = zero;
        for (Journal journal : journals) {
            if (journal.valueDay() <= valueDay) {
                balance = balance.add(journal.netFor(accountId, zero));
            }
        }
        return balance;
    }

    /** Latest lifecycle record per authorization id, in first-seen order. */
    public Map<String, AuthRecord> latestAuthStates(String accountId) {
        Map<String, AuthRecord> latest = new LinkedHashMap<>();
        for (AuthRecord record : authRecords) {
            if (record.accountId().equals(accountId)) {
                latest.put(record.authId(), record);
            }
        }
        return latest;
    }

    public Optional<AuthRecord> latestAuthState(String accountId, String authId) {
        return Optional.ofNullable(latestAuthStates(accountId).get(authId));
    }

    // ---- reconciliation ----

    /**
     * Recomputes every cached balance from the records and checks the double-entry invariant.
     *
     * @return one line per discrepancy; empty when the books agree
     */
    public List<String> reconcile() {
        List<String> problems = new ArrayList<>();
        Map<Currency, BigDecimal> netByCurrency = new EnumMap<>(Currency.class);
        for (Account account : accounts.values()) {
            BigDecimal zero = account.currency().zero();
            BigDecimal folded = zero;
            for (Journal journal : journals) {
                folded = folded.add(journal.netFor(account.id(), zero));
            }
            if (folded.compareTo(ledgerBalanceCache.get(account.id())) != 0) {
                problems.add(account.id() + " ledger balance cache " + ledgerBalanceCache.get(account.id())
                        + " != folded " + folded);
            }
            netByCurrency.merge(account.currency(), folded, BigDecimal::add);

            BigDecimal holds = zero;
            for (AuthRecord latest : latestAuthStates(account.id()).values()) {
                if (latest.status() == AuthStatus.APPROVED) {
                    holds = holds.add(latest.holdAmount());
                }
            }
            if (holds.compareTo(activeHoldsCache.get(account.id())) != 0) {
                problems.add(account.id() + " active holds cache " + activeHoldsCache.get(account.id())
                        + " != folded " + holds);
            }
        }
        netByCurrency.forEach((currency, net) -> {
            if (net.signum() != 0) {
                problems.add("Ledger does not balance in " + currency + ": net " + net);
            }
        });
        return problems;
    }
}
