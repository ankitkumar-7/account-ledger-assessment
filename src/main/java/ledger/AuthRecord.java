package ledger;

import java.math.BigDecimal;

/**
 * One immutable step in an authorization's lifecycle. Its current state is the latest record for
 * its id; earlier records are never rewritten. Each record also stores the balances the decision
 * was made on, so the decision can be explained without replaying history.
 *
 * @param holdAmount     the amount authorized (and held, while APPROVED)
 * @param settledAmount  the amount actually settled; null unless SETTLED
 * @param ledgerBalance  ledger balance when the decision was made
 * @param activeHolds    active holds when the decision was made, before this record
 * @param availableAfter available balance after this record took effect; for DECLINED, what it
 *                       would have been had the hold been placed (the figure that failed the rule)
 */
public record AuthRecord(
        long sequence,
        String accountId,
        String authId,
        AuthStatus status,
        BigDecimal holdAmount,
        BigDecimal settledAmount,
        int day,
        String sourceEventId,
        BigDecimal ledgerBalance,
        BigDecimal activeHolds,
        BigDecimal availableAfter) {
}
