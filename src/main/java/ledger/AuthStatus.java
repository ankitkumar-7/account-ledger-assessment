package ledger;

public enum AuthStatus {
    /** Hold placed; counts against available balance. */
    APPROVED,
    /** Refused at request time; never held anything. */
    DECLINED,
    /** Hold removed because a settlement consumed it. */
    SETTLED
}
