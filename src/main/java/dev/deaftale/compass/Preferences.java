package dev.deaftale.compass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

final class Preferences {
    record Options(boolean enabled, boolean large, boolean quiet) {}
    private final Path file;
    private final Properties data = new Properties();
    Preferences(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("players.properties");
        if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { data.load(input); }
    }
    synchronized Options get(UUID id) {
        return new Options(Boolean.parseBoolean(data.getProperty(id + ".enabled", "true")),
                Boolean.parseBoolean(data.getProperty(id + ".large", "false")),
                Boolean.parseBoolean(data.getProperty(id + ".quiet", "false")));
    }
    synchronized void save(UUID id, Options options) throws IOException {
        data.setProperty(id + ".enabled", Boolean.toString(options.enabled));
        data.setProperty(id + ".large", Boolean.toString(options.large));
        data.setProperty(id + ".quiet", Boolean.toString(options.quiet));
        Path temp = Files.createTempFile(file.getParent(), "players-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) { data.store(output, "Sound Compass personal settings"); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
