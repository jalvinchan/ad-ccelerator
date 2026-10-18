package lab.youtube.accelerator;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedSliderTest {
    @Test public void quickIndexMatchesWholeAndFractionalQuickPicks() {
        assertEquals(0, SpeedSlider.quickIndex(1));
        assertEquals(1, SpeedSlider.quickIndex(1.25));
        assertEquals(2, SpeedSlider.quickIndex(1.5));
        assertEquals(3, SpeedSlider.quickIndex(2));
        assertEquals(1, SpeedSlider.quickIndex(1.2500000000000002));
        assertEquals(-1, SpeedSlider.quickIndex(1.3));
        assertEquals(-1, SpeedSlider.quickIndex(0.25));
        assertEquals(-1, SpeedSlider.quickIndex(4));
    }
}
