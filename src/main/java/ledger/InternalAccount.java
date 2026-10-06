package ledger;

/** Bank-side counter-accounts. One instance of each exists per currency. */
public enum InternalAccount {
    /** Funds arriving from or leaving to outside the bank (transfers in/out). */
    EXTERNAL_CLEARING,
    /** Amounts owed to the card scheme for settled card transactions. */
    CARD_SCHEME_SETTLEMENT,
    FEE_INCOME,
    INTEREST_EXPENSE;

    public String idFor(Currency currency) {
        return "GL-" + name() + "-" + currency;
    }
}
