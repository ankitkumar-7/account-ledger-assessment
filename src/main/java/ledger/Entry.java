package ledger;

import java.math.BigDecimal;

/**
 * One immutable posting. {@code amount} is signed: positive credits the account, negative debits
 * it. {@code valueDay} decides which day's balance the entry belongs to; {@code bookedDay} is the
 * processing day on which it was actually written. They differ for back-valued entries.
 *
 * @param reference the event or entry this posting relates to (reversed event, settled auth, fee
 *                  day), or null
 */
public record Entry(
        long sequence,
        String accountId,
        EntryType type,
        BigDecimal amount,
        int valueDay,
        int bookedDay,
        String sourceEventId,
        String reference) {
}
