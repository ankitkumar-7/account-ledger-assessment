package ledger;

/** An event (or end-of-day action) the ledger refused, kept so errors are part of the record. */
public record Rejection(int day, String eventId, String accountId, String reason) {
}
