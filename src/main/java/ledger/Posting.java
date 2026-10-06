package ledger;

import java.math.BigDecimal;

/** One leg of a journal. Signed: positive credits the account, negative debits it. */
public record Posting(String accountId, BigDecimal amount) {
}
