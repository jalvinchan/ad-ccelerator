package lab.youtube.accelerator;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeProtocolTest {
    @Test public void scalarsRejectCoercionAndNonFiniteValues() {
        for (double rate : NativeProtocol.CONTENT_RATES) assertTrue(NativeProtocol.contentRate(rate));
        for (Object value : new Object[] {"2", true, null, Double.NaN, Double.POSITIVE_INFINITY, 16, 1.1})
            assertFalse(NativeProtocol.contentRate(value));
        assertTrue(NativeProtocol.integer(3L, 3));
        assertFalse(NativeProtocol.integer("3", 3));
        assertFalse(NativeProtocol.integer(3.1, 3));
        assertFalse(NativeProtocol.context(""));
        assertFalse(NativeProtocol.context("x".repeat(201)));
        assertTrue(NativeProtocol.optionalRate(null));
        assertFalse(NativeProtocol.optionalRate(-1));
        for (double rate : new double[] {1.1, 2.5, 8, 16}) assertTrue(NativeProtocol.optionalRate(rate));
        for (Object value : new Object[] {"2", true, 0, 16.1, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(NativeProtocol.optionalRate(value));
    }
}
