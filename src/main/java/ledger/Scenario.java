package ledger;

import java.math.BigDecimal;
import java.util.List;

/** The accounts and event stream from the brief, in replay order. */
public final class Scenario {

    public static final String ACC_001 = "ACC-001";
    public static final String ACC_002 = "ACC-002";

    private Scenario() {
    }

    public static LedgerEngine openAccounts() {
        LedgerEngine engine = new LedgerEngine();
        engine.openCustomerAccount(ACC_001, Currency.AED, new BigDecimal("0.00"));
        engine.openCustomerAccount(ACC_002, Currency.BHD, new BigDecimal("0.000"));
        return engine;
    }

    public static List<Event> events() {
        return List.of(
                new Event.Credit("E1", 1, ACC_001, aed("1200.00"), 1),
                new Event.Debit("E2", 1, ACC_001, aed("950.00"), 1),
                new Event.Authorization("E3", 2, ACC_001, "Auth-A", aed("200.00"), 2),
                new Event.Credit("E4", 3, ACC_001, aed("400.00"), 3),
                new Event.Settlement("E5", 4, ACC_001, "Auth-A", aed("185.00"), 4),
                new Event.Settlement("E6", 4, ACC_001, "Auth-Z", aed("180.00"), 4),
                new Event.Debit("E7", 5, ACC_001, aed("620.00"), 2),
                new Event.Authorization("E8", 5, ACC_001, "Auth-B", aed("90.00"), 5),
                new Event.Reversal("E9", 6, ACC_001, "E7", 2),
                new Event.InstalmentCredit("E10", 5, ACC_002, new BigDecimal("10.000"), 3, 5));
    }

    private static BigDecimal aed(String amount) {
        return new BigDecimal(amount);
    }
}
