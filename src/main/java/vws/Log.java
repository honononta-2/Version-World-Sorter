package vws;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// gameDir/logs/version-world-sorter.log に診断ログを追記する
public final class Log {
    private static volatile Path file;

    private Log() {
    }

    public static void setFile(Path p) {
        file = p;
    }

    public static void log(String msg) {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, ("[VWS] " + msg + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    public static void log(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(msg + ": " + t + System.lineSeparator() + sw);
    }
}
