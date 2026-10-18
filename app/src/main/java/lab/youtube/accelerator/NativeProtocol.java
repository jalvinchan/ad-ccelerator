package lab.youtube.accelerator;

/** Strict scalar checks shared by the native message handlers. Never coerce webpage data. */
final class NativeProtocol {
    static final double MIN_RATE = 0.25;
    static final double MAX_RATE = 4;
    static final double RATE_STEP = 0.05;
    static final int RATE_STEPS = (int) Math.round((MAX_RATE - MIN_RATE) / RATE_STEP);
    static final int[] SEEK_CHOICES = {5, 10, 15, 30};
    /** Nearest selectable rate, kept free of binary-fraction noise such as 1.0500000000000003. */
    static double snapRate(double rate) {
        double clamped = Math.min(MAX_RATE, Math.max(MIN_RATE, rate));
        return Math.round(clamped / RATE_STEP) / (double) Math.round(1 / RATE_STEP);
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
    static boolean seekInterval(int seconds) {
        for (int choice : SEEK_CHOICES) if (choice == seconds) return true;
        return false;
    }
    private NativeProtocol() {}
}
