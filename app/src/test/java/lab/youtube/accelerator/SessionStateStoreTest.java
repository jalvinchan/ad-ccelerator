package lab.youtube.accelerator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class SessionStateStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void saveThenTakeRoundTripsLargeAndNonAsciiState() throws Exception {
        File file = new File(folder.getRoot(), SessionStateStore.FILE_NAME);
        String state = "{\"history\":\"é×\"}".concat("x".repeat(2 * 1024 * 1024));
        SessionStateStore.save(file, state);
        assertEquals(state, SessionStateStore.take(file));
    }

    @Test public void takeDeletesTheFile() throws Exception {
        File file = new File(folder.getRoot(), SessionStateStore.FILE_NAME);
        SessionStateStore.save(file, "state");
        assertEquals("state", SessionStateStore.take(file));
        assertNull(SessionStateStore.take(file));
    }

    @Test public void takeMissingFileReturnsNull() {
        File file = new File(folder.getRoot(), "missing.json");
        assertNull(SessionStateStore.take(file));
    }

    @Test public void saveOverwritesAnExistingFile() throws Exception {
        File file = new File(folder.getRoot(), SessionStateStore.FILE_NAME);
        SessionStateStore.save(file, "old");
        SessionStateStore.save(file, "new");
        assertEquals("new", SessionStateStore.take(file));
    }

    @Test public void clearOnMissingFileDoesNotThrow() {
        SessionStateStore.clear(new File(folder.getRoot(), "missing.json"));
    }
}
