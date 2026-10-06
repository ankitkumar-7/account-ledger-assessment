package ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * One business transaction: an immutable, balanced set of postings (they sum to zero per
 * currency). {@code valueDay} decides which day's balance it belongs to; {@code bookedDay} and
 * {@code sequence} record when the ledger actually learned of it. They differ for back-valued
 * journals.
 *
 * @param reference what this journal relates to: the reversed journal, the settled authorization,
 *                  the fee day; null when nothing
 * @param detail    the inputs that produced this journal, for audit (e.g. the balance that
 *                  triggered a fee)
 */
public record Journal(
        long sequence,
        JournalType type,
        int valueDay,
        int bookedDay,
        String sourceEventId,
        String reference,
        String detail,
        List<Posting> postings) {

    public Journal {
        postings = List.copyOf(postings);
    }

    public boolean touches(String accountId) {
        return postings.stream().anyMatch(p -> p.accountId().equals(accountId));
    }

    /** Net effect of this journal on one account (an account may appear on several legs). */
    public BigDecimal netFor(String accountId, BigDecimal zero) {
        BigDecimal net = zero;
        for (Posting posting : postings) {
            if (posting.accountId().equals(accountId)) {
                net = net.add(posting.amount());
            }
        }
        return net;
    }
}
