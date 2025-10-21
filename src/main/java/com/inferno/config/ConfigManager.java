package com.inferno.config;

import com.inferno.database.CdfRepository;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Handles on-disk configuration under ~/.config/cdf (or XDG equivalent).
 * Maintains:
 * - .cdfRoots : ordered list of root directories with priorities
 * - .cdfIgnore: global ignore patterns (gitignore-style)
 */
public final class ConfigManager {
    public static final String ROOTS_FILENAME = ".cdfRoots";
    public static final String IGNORE_FILENAME = ".cdfIgnore";
    private static final int DEFAULT_PRIORITY = 100;

    public record RootEntry(String path, int priority) {}

    private final Path configDir;
    private final Path rootsFile;
    private final Path ignoreFile;

    public ConfigManager() {
        this(resolveConfigDir());
    }

    public ConfigManager(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir).toAbsolutePath();
        this.rootsFile = this.configDir.resolve(ROOTS_FILENAME);
        this.ignoreFile = this.configDir.resolve(IGNORE_FILENAME);
    }

    /**
     * Ensure the config directory and default files exist.
     */
    public void ensureLayout() throws IOException {
        Files.createDirectories(configDir);
        if (Files.notExists(rootsFile)) {
            try (BufferedWriter writer = Files.newBufferedWriter(rootsFile, StandardCharsets.UTF_8)) {
                writer.write("# cdf roots file\n");
                writer.write("# Format: <priority> <absolute-path>\n");
            }
        }
        if (Files.notExists(ignoreFile)) {
            try (BufferedWriter writer = Files.newBufferedWriter(ignoreFile, StandardCharsets.UTF_8)) {
                writer.write("# cdf global ignore patterns (gitignore syntax)\n");
                writer.write("# Example:\n");
                writer.write("# node_modules/\n");
            }
        }
    }

    public Path configDir() {
        return configDir;
    }

    public Path rootsFile() {
        return rootsFile;
    }

    public Path ignoreFile() {
        return ignoreFile;
    }

    /**
     * Load root entries from the .cdfRoots file.
     */
    public List<RootEntry> loadRoots() throws IOException {
        if (Files.notExists(rootsFile)) {
            return Collections.emptyList();
        }
        List<RootEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(rootsFile, StandardCharsets.UTF_8)) {
            RootEntry entry = parseRootLine(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Add or update a root in both config file and, optionally, repository (via sync method).
     */
    public void addOrUpdateRoot(Path rootPath, int priority) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath");
        Map<String, RootEntry> byPath = new LinkedHashMap<>();
        for (RootEntry entry : loadRoots()) {
            byPath.put(normalise(entry.path()), entry);
        }
        String key = normalise(rootPath.toString());
        byPath.put(key, new RootEntry(key, priority));
        writeRoots(new ArrayList<>(byPath.values()));
    }

    /**
     * Synchronise configured roots into the repository (upsert).
     */
    public void syncRoots(CdfRepository repository) throws SQLException, IOException {
        Objects.requireNonNull(repository, "repository");
        for (RootEntry entry : loadRoots()) {
            String path = normalise(entry.path());
            repository.upsertRoot(path, entry.priority());
        }
    }

    public List<String> loadGlobalIgnorePatterns() throws IOException {
        if (Files.notExists(ignoreFile)) {
            return List.of();
        }
        return trimComments(Files.readAllLines(ignoreFile, StandardCharsets.UTF_8));
    }

    public List<String> loadRootIgnorePatterns(Path rootPath) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath");
        Path rootIgnore = rootPath.resolve(IGNORE_FILENAME);
        if (Files.notExists(rootIgnore)) {
            return List.of();
        }
        return trimComments(Files.readAllLines(rootIgnore, StandardCharsets.UTF_8));
    }

    public IgnoreRules buildIgnoreRulesForRoot(Path rootPath) throws IOException {
        List<String> combined = new ArrayList<>();
        combined.addAll(loadGlobalIgnorePatterns());
        combined.addAll(loadRootIgnorePatterns(rootPath));
        return IgnoreRules.compile(combined);
    }

    private static List<String> trimComments(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.stripTrailing();
            cleaned.add(trimmed);
        }
        return cleaned;
    }

    private void writeRoots(List<RootEntry> entries) throws IOException {
        entries.sort(Comparator
                .comparingInt(RootEntry::priority)
                .thenComparing(RootEntry::path, String.CASE_INSENSITIVE_ORDER));
        try (BufferedWriter writer = Files.newBufferedWriter(rootsFile, StandardCharsets.UTF_8)) {
            writer.write("# cdf roots file\n");
            writer.write("# Format: <priority> <absolute-path>\n");
            for (RootEntry entry : entries) {
                writer.write(entry.priority() + " " + entry.path());
                writer.newLine();
            }
        }
    }

    private static RootEntry parseRootLine(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }

        String priorityToken;
        String pathToken;

        int firstWhitespace = indexOfWhitespace(line);
        if (firstWhitespace < 0) {
            priorityToken = null;
            pathToken = line;
        } else {
            priorityToken = line.substring(0, firstWhitespace).trim();
            pathToken = line.substring(firstWhitespace + 1).trim();
        }

        int priority = DEFAULT_PRIORITY;
        if (priorityToken != null && looksNumeric(priorityToken)) {
            try {
                priority = Integer.parseInt(priorityToken);
            } catch (NumberFormatException ignore) {
                // leave default priority
                pathToken = line;
            }
        } else if (priorityToken != null && !priorityToken.isEmpty()) {
            // The token was actually part of the path.
            pathToken = line;
        }

        if (pathToken == null || pathToken.isEmpty()) {
            return null;
        }
        return new RootEntry(normalise(pathToken), priority);
    }

    private static int indexOfWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean looksNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 0 && (c == '+' || c == '-')) continue;
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    private static String normalise(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private static Path resolveConfigDir() {
        String override = System.getenv("CDF_CONFIG_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "cdf");
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home is not set; cannot resolve config directory");
        }
        return Path.of(home, ".config", "cdf");
    }
}
