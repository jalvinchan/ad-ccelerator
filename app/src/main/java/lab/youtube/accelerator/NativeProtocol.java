package lab.youtube.accelerator;

/** Strict scalar checks shared by the native message handlers. Never coerce webpage data. */
final class NativeProtocol {
    static final double[] CONTENT_RATES = {0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.25, 3, 4};
    static boolean contentRate(Object value) {
        if (!(value instanceof Number)) return false;
        double rate = ((Number) value).doubleValue();
        for (double allowed : CONTENT_RATES) if (rate == allowed) return true;
        return false;
    }
    static boolean integer(Object value, int expected) {
        return value instanceof Number && ((Number) value).doubleValue() == expected;
    }
    static boolean context(Object value) {
        return value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= 200;
    }
    static boolean fraction(Object value) {
        return value instanceof Number && ((Number) value).doubleValue() >= 0 && ((Number) value).doubleValue() <= 1;
    }
    static boolean optionalRate(Object value) {
        return value == null || value instanceof Number && Double.isFinite(((Number) value).doubleValue())
            && ((Number) value).doubleValue() > 0 && ((Number) value).doubleValue() <= 16;
    }
    private NativeProtocol() {}
}
