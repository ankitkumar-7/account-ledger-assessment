package ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of capitalising interest for one account.
 *
 * @param closingBalances the value-dated closing balance each accrual was computed on, index 0 = Day 1
 * @param dailyAccruals   each day's rounded accrual, index 0 = Day 1
 * @param total           the capitalised amount; always exactly the sum of {@code dailyAccruals}
 * @param journal         the INTEREST journal posted, or null when the total is zero
 */
public record InterestCapitalisation(
        String accountId,
        List<BigDecimal> closingBalances,
        List<BigDecimal> dailyAccruals,
        BigDecimal total,
        Journal journal) {
}
