package lab.youtube.accelerator;

import org.junit.Test;
import static org.junit.Assert.*;

public class TickedSeekBarTest {
    @Test public void longTicksAtWholeSpeeds() {
        for (int index : new int[] {0, 15, 35, 55, 75})
            assertEquals("index " + index, TickedSeekBar.LONG, TickedSeekBar.tickLevel(index));
    }

    @Test public void mediumTicksAtHalfSpeeds() {
        for (int index : new int[] {5, 25, 45, 65})
            assertEquals("index " + index, TickedSeekBar.MEDIUM, TickedSeekBar.tickLevel(index));
    }

    @Test public void shortTicksAtQuarterSpeeds() {
        for (int index : new int[] {10, 20, 30, 40, 50, 60, 70})
            assertEquals("index " + index, TickedSeekBar.SHORT, TickedSeekBar.tickLevel(index));
    }

    @Test public void exactlySixteenTicks() {
        int count = 0;
        for (int index = 0; index <= NativeProtocol.RATE_STEPS; index++)
            if (TickedSeekBar.tickLevel(index) != TickedSeekBar.NONE) count++;
        assertEquals(16, count);
    }

    @Test public void noTicksAtOtherStepsOrOutOfRange() {
        for (int index : new int[] {1, 4, 6, 14, 74, -1, 76})
            assertEquals("index " + index, TickedSeekBar.NONE, TickedSeekBar.tickLevel(index));
    }
}
