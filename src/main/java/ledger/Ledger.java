package ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only store of entries, authorization records and rejections. There is no update or
 * delete operation: every balance and every authorization state is derived by folding over the
 * records, so a correction is always a new record (e.g. a REVERSAL entry), never an edit.
 *
 * <p>Balances are recomputed from scratch on every query. That is deliberate for a six-day
 * window (it makes back-valued entries trivially correct) and is the first thing that would
 * have to change at volume — see the architecture document.
 */
public final class Ledger {

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<AuthRecord> authRecords = new ArrayList<>();
    private final List<Rejection> rejections = new ArrayList<>();
    private long sequence;

    public void openAccount(Account account) {
        if (accounts.putIfAbsent(account.id(), account) != null) {
            throw new IllegalArgumentException("Account already exists: " + account.id());
        }
    }

    public Account account(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Unknown account: " + accountId);
        }
        return account;
    }

    public List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    // ---- writes (package-private: only the engine appends) ----

    Entry append(String accountId, EntryType type, BigDecimal signedAmount, int valueDay, int bookedDay,
                 String sourceEventId, String reference) {
        BigDecimal amount = account(accountId).currency().normalise(signedAmount);
        Entry entry = new Entry(++sequence, accountId, type, amount, valueDay, bookedDay, sourceEventId, reference);
        entries.add(entry);
        return entry;
    }

    AuthRecord appendAuth(String accountId, String authId, AuthStatus status, BigDecimal amount, int day,
                          String sourceEventId, String detail) {
        BigDecimal normalised = account(accountId).currency().normalise(amount);
        AuthRecord record = new AuthRecord(++sequence, accountId, authId, status, normalised, day, sourceEventId, detail);
        authRecords.add(record);
        return record;
    }

    Rejection reject(int day, String eventId, String accountId, String reason) {
        Rejection rejection = new Rejection(day, eventId, accountId, reason);
        rejections.add(rejection);
        return rejection;
    }

    // ---- read-only views ----

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<AuthRecord> authRecords() {
        return Collections.unmodifiableList(authRecords);
    }

    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }

    // ---- derived balances ----

    /** Closing ledger balance of {@code valueDay}: opening balance plus every entry with value_date ≤ that day. */
    public BigDecimal closingBalance(String accountId, int valueDay) {
        BigDecimal balance = account(accountId).openingBalance();
        for (Entry entry : entries) {
            if (entry.accountId().equals(accountId) && entry.valueDay() <= valueDay) {
                balance = balance.add(entry.amount());
            }
        }
        return balance;
    }

    /** Current ledger balance: every entry booked so far, regardless of value date. */
    public BigDecimal ledgerBalance(String accountId) {
        BigDecimal balance = account(accountId).openingBalance();
        for (Entry entry : entries) {
            if (entry.accountId().equals(accountId)) {
                balance = balance.add(entry.amount());
            }
        }
        return balance;
    }

    public BigDecimal activeHolds(String accountId) {
        BigDecimal holds = account(accountId).currency().zero();
        for (AuthRecord latest : latestAuthStates(accountId).values()) {
            if (latest.status() == AuthStatus.APPROVED) {
                holds = holds.add(latest.amount());
            }
        }
        return holds;
    }

    public BigDecimal availableBalance(String accountId) {
        return ledgerBalance(accountId).subtract(activeHolds(accountId));
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

    public boolean hasEntry(String accountId, EntryType type, int valueDay) {
        return entries.stream().anyMatch(e ->
                e.accountId().equals(accountId) && e.type() == type && e.valueDay() == valueDay);
    }

    public List<Entry> entriesFromEvent(String sourceEventId) {
        return entries.stream().filter(e -> e.sourceEventId().equals(sourceEventId)).toList();
    }
}
