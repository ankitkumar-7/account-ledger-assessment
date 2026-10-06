package ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Every business constant in one place. Rationale for each value is in NUMBERS.md. */
public final class Rules {

    public static final int FIRST_DAY = 1;
    public static final int LAST_DAY = 6;

    public static final Currency OVERDRAFT_FEE_CURRENCY = Currency.AED;
    public static final BigDecimal OVERDRAFT_FEE = new BigDecimal("25.00");

    /** 0.04% per day, held exactly; never derived from an annual rate. */
    public static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.0004");

    /** Rounding for each daily interest accrual. */
    public static final RoundingMode INTEREST_ROUNDING = RoundingMode.HALF_EVEN;

    /** How far a settlement may exceed its authorization's hold. Zero: no over-capture. */
    public static final BigDecimal SETTLEMENT_OVER_AUTH_TOLERANCE = BigDecimal.ZERO;

    private Rules() {
    }
}
