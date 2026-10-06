package ledger;

import java.math.BigDecimal;

/**
 * One immutable step in an authorization's lifecycle. An authorization's current state is the
 * latest record for its id; earlier records are never rewritten.
 *
 * @param amount the hold amount for APPROVED/DECLINED, the settled amount for SETTLED
 */
public record AuthRecord(
        long sequence,
        String accountId,
        String authId,
        AuthStatus status,
        BigDecimal amount,
        int day,
        String sourceEventId,
        String detail) {
}
