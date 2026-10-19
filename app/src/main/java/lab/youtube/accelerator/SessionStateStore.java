package lab.youtube.accelerator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Keeps GeckoView's session state out of the Binder-limited saved-instance Bundle. */
final class SessionStateStore {
    static final String FILE_NAME = "session-state.json";
    static final String WATCH_FILE_NAME = "watch-state.json";

    /** Writes atomically (temp file + rename). Throws on I/O failure. */
    static void save(File file, String state) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        Files.write(temp.toPath(), state.getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), file.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Returns the file's UTF-8 contents and deletes the file; null if missing or unreadable. Never throws. */
    static String take(File file) {
        if (!file.isFile()) return null;
        try {
            String state = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return state.isEmpty() ? null : state;
        } catch (IOException error) {
            return null;
        } finally {
            clear(file);
        }
    }

    /** Deletes the file if present. Never throws. */
    static void clear(File file) {
        file.delete();
        new File(file.getPath() + ".tmp").delete();
    }

    private SessionStateStore() {}
}
