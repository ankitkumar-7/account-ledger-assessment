package ledger;

import java.math.BigDecimal;

/** Currencies the ledger supports, each with its ISO 4217 minor-unit precision. */
public enum Currency {
    AED(2),
    BHD(3);

    private final int scale;

    Currency(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    public BigDecimal zero() {
        return BigDecimal.ZERO.setScale(scale);
    }

    public BigDecimal amount(String value) {
        return normalise(new BigDecimal(value));
    }

    /**
     * Brings an amount to this currency's exact scale. Refuses (rather than silently rounds) any
     * amount carrying more precision than the currency has: rounding is a business decision and is
     * only ever done explicitly by the caller that owns it (interest, instalment split).
     */
    public BigDecimal normalise(BigDecimal value) {
        if (value.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException(
                    value.toPlainString() + " has more than " + scale + " decimal places for " + this);
        }
        return value.setScale(scale);
    }
}
