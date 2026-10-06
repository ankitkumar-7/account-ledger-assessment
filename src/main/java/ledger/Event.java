package ledger;

import java.math.BigDecimal;

/** An input to the ledger. {@code day} is the processing day the event is stamped with. */
public sealed interface Event {

    String id();

    int day();

    String accountId();

    record Credit(String id, int day, String accountId, BigDecimal amount, int valueDay) implements Event {
    }

    record Debit(String id, int day, String accountId, BigDecimal amount, int valueDay) implements Event {
    }

    record Authorization(String id, int day, String accountId, String authId, BigDecimal amount, int valueDay)
            implements Event {
    }

    record Settlement(String id, int day, String accountId, String authId, BigDecimal amount, int valueDay)
            implements Event {
    }

    record Reversal(String id, int day, String accountId, String reversedEventId, int valueDay) implements Event {
    }

    /** A single credit delivered as {@code parts} postings that must sum exactly to {@code total}. */
    record InstalmentCredit(String id, int day, String accountId, BigDecimal total, int parts, int valueDay)
            implements Event {
    }
}
