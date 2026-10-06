package ledger;

public record Account(String id, Currency currency, AccountKind kind) {

    public enum AccountKind {
        /** A customer deposit account. */
        CUSTOMER,
        /** A bank-side general-ledger account: the counter-side of customer postings. */
        INTERNAL
    }
}
