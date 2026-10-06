package ledger;

import java.math.BigDecimal;
import java.util.Objects;

/** Minimal assertions; the project deliberately has no test-framework dependency. */
final class Check {

    private Check() {
    }

    /**
     * Compares an amount by its exact string form, so scale is asserted too: "390.93" passes,
     * "390.930" or "390.9" would not. Precision is part of the rule being tested.
     */
    static void amount(String expected, BigDecimal actual, String what) {
        if (actual == null || !expected.equals(actual.toPlainString())) {
            throw new AssertionError(what + ": expected " + expected + " but was "
                    + (actual == null ? "null" : actual.toPlainString()));
        }
    }

    static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    static void isTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void throwsException(Class<? extends Throwable> type, Runnable action, String what) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + thrown);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
