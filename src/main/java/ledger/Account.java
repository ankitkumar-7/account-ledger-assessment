package ledger;

import java.math.BigDecimal;

public record Account(String id, Currency currency, BigDecimal openingBalance) {

    public Account {
        openingBalance = currency.normalise(openingBalance);
    }
}
