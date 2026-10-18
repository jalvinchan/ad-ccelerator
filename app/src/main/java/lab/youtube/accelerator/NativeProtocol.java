package lab.youtube.accelerator;

/** Strict scalar checks shared by the native message handlers. Never coerce webpage data. */
final class NativeProtocol {
    static final double MIN_RATE = 0.25;
    static final double MAX_RATE = 4;
    static final double RATE_STEP = 0.05;
    static final int RATE_STEPS = (int) Math.round((MAX_RATE - MIN_RATE) / RATE_STEP);
    static boolean contentRate(Object value) {
        if (!(value instanceof Number)) return false;
        double rate = ((Number) value).doubleValue();
        return rate >= MIN_RATE && rate <= MAX_RATE && Math.abs(rate / RATE_STEP - Math.round(rate / RATE_STEP)) < 1e-6;
    }
    /** Nearest selectable rate, kept free of binary-fraction noise such as 1.0500000000000003. */
    static double snapRate(double rate) {
        double clamped = Math.min(MAX_RATE, Math.max(MIN_RATE, rate));
        return Math.round(clamped / RATE_STEP) * 5 / 100.0;
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
