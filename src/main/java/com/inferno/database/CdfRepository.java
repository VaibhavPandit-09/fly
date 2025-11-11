package com.inferno.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain Java repository layer for fly.
 * - Single embedded SQLite database (~/.local/share/fly/index.sqlite by default; legacy ~/.local/share/cdf/index.sqlite is honoured)
 * - Creates schema on first run
 * - Exposes CRUD for roots and directories
 * - Provides batch upserts, deletions, basic queries, MRU updates, and stats
 *
 * Dependency: org.xerial:sqlite-jdbc (put it on the classpath)
 * JDK: 21+ (records used for DTOs)
 */
public final class CdfRepository implements AutoCloseable {

    // === DTOs (records keep it compact and immutable) ===
    public record Root(int id, String path) {}
    public record Directory(
            int id,
            String basename,
            String fullpath,
            int depth,
            int rootId,
            long mtimeEpochSeconds,
            Long lastUsedEpochSeconds, // nullable
            String segments // slash-delimited e.g. "repo/java_app/src/main"
    ) {}

    private final Path dbFilePath;
    private Connection conn;

    // === Schema DDL (idempotent) ===
    private static final String SCHEMA_DDL = """
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS roots (
          id       INTEGER PRIMARY KEY,
          path     TEXT NOT NULL UNIQUE
        );

        CREATE TABLE IF NOT EXISTS directories (
          id         INTEGER PRIMARY KEY,
          basename   TEXT NOT NULL,
          fullpath   TEXT NOT NULL UNIQUE,
          depth      INTEGER NOT NULL,
          root_id    INTEGER NOT NULL REFERENCES roots(id) ON DELETE CASCADE,
          mtime      INTEGER NOT NULL,
          last_used  INTEGER,
          segments   TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS lastcall (
          id         INTEGER PRIMARY KEY,
          paths      TEXT NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_dirs_basename
          ON directories (basename);

        CREATE INDEX IF NOT EXISTS idx_dirs_root_basename
          ON directories (root_id, basename);
        """;

    // === Constructor ===

    /**
     * Create a repository bound to the default data directory (XDG-aware).
     * The database file is created automatically on first connect.
     */
    public CdfRepository() {
        this(resolveDefaultDatabasePath());
    }

    /**
     * Create a repository bound to a custom file path.
     */
    public CdfRepository(Path dbFilePath) {
        this.dbFilePath = Objects.requireNonNull(dbFilePath).toAbsolutePath();
    }

    // === Lifecycle ===

    /**
     * Opens the connection if not already open, applies WAL and sensible PRAGMAs, and ensures schema exists.
     */
    public synchronized void open() throws SQLException, IOException {
        if (this.conn != null && !this.conn.isClosed()) return;

        Files.createDirectories(dbFilePath.getParent());
        final String url = "jdbc:sqlite:" + dbFilePath;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found on classpath", e);
        }

        this.conn = DriverManager.getConnection(url);
        try (Statement s = conn.createStatement()) {
            // Speed + concurrency
            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA synchronous=NORMAL;");
            s.execute("PRAGMA temp_store=MEMORY;");
            s.execute("PRAGMA foreign_keys=ON;");
        }

        // Create schema if missing
        try (Statement s = conn.createStatement()) {
            s.executeUpdate(SCHEMA_DDL);
        }
        migrateLegacyPriorityColumn();
    }

    //Get lastcall paths
    public List<String> getLastCallPaths() throws SQLException {
        final String sql = "SELECT paths FROM lastcall WHERE id = 1";
        Optional<String> paths;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                 paths = rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
        if (paths.isEmpty()) {
            return new ArrayList<>();
        }
        String pathStr = paths.get().trim();
        if (pathStr.isEmpty()) {
            return new ArrayList<>();
        }
        String[] pathArr = pathStr.split(";");
        List<String> pathList = new ArrayList<>(pathArr.length);
        for (String p : pathArr) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                pathList.add(trimmed);
            }
        }
        return pathList;
    }

    // replace lastcall paths
    public void replaceLastCallPaths(List<String> paths) throws SQLException {
        if (paths == null || paths.isEmpty()) {
            try (PreparedStatement ps = requireConn().prepareStatement("DELETE FROM lastcall WHERE id = 1")) {
                ps.executeUpdate();
            }
            return;
        }

        final String sqlInsert = """
            INSERT INTO lastcall (id, paths)
            VALUES (1, ?)
            ON CONFLICT(id) DO UPDATE SET paths = excluded.paths
            """;
        String joinedPaths = String.join(";", paths);
        try (PreparedStatement ps = requireConn().prepareStatement(sqlInsert)) {
            ps.setString(1, joinedPaths);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() {
        if (this.conn != null) {
            try { this.conn.close(); } catch (SQLException ignore) {}
            this.conn = null;
        }
    }

    private Connection requireConn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            throw new SQLException("Repository not opened. Call open() first.");
        }
        return conn;
    }

    // === Utility ===

    public Path dbFilePath() { return dbFilePath; }

    public String pragmaIntegrityCheck() throws SQLException {
        try (PreparedStatement ps = requireConn().prepareStatement("PRAGMA integrity_check;")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "no result";
            }
        }
    }

    // =========================================================================================
    // ROOTS — CRUD
    // =========================================================================================

    /**
     * Upsert a root by absolute path; returns root id.
     */
    public int upsertRoot(String path) throws SQLException {
        Objects.requireNonNull(path);
        final String sql = """
            INSERT INTO roots(path)
            VALUES(?)
            ON CONFLICT(path) DO NOTHING
            """;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, path);
            ps.executeUpdate();
        }
        return getRootIdByPath(path).orElseThrow(() -> new SQLException("Failed to fetch root id after upsert"));
    }

    public Optional<Integer> getRootIdByPath(String path) throws SQLException {
        final String sql = "SELECT id FROM roots WHERE path = ?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
            }
        }
    }

    public boolean deleteRootByPath(String path) throws SQLException {
        final String sql = "DELETE FROM roots WHERE path = ?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, path);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Root> listRoots() throws SQLException {
        final String sql = "SELECT id, path FROM roots ORDER BY path COLLATE NOCASE ASC, id ASC";
        try (PreparedStatement ps = requireConn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Root> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new Root(rs.getInt(1), rs.getString(2)));
            }
            return list;
        }
    }

    // =========================================================================================
    // DIRECTORIES — CRUD
    // =========================================================================================

    /**
     * Upsert a single directory.
     */
    public void upsertDirectory(Directory d) throws SQLException {
        Objects.requireNonNull(d);
        final String sql = """
            INSERT INTO directories (basename, fullpath, depth, root_id, mtime, last_used, segments)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(fullpath) DO UPDATE SET
              basename = excluded.basename,
              depth    = excluded.depth,
              root_id  = excluded.root_id,
              mtime    = excluded.mtime,
              segments = excluded.segments
            """;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, d.basename);
            ps.setString(2, d.fullpath);
            ps.setInt(3, d.depth);
            ps.setInt(4, d.rootId);
            ps.setLong(5, d.mtimeEpochSeconds);
            if (d.lastUsedEpochSeconds == null) ps.setNull(6, Types.INTEGER);
            else ps.setLong(6, d.lastUsedEpochSeconds);
            ps.setString(7, d.segments);
            ps.executeUpdate();
        }
    }

    /**
     * Batch upsert directories inside a single transaction (critical for speed).
     */
    public void batchUpsertDirectories(List<Directory> dirs) throws SQLException {
        if (dirs == null || dirs.isEmpty()) return;
        final String sql = """
            INSERT INTO directories (basename, fullpath, depth, root_id, mtime, last_used, segments)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(fullpath) DO UPDATE SET
              basename = excluded.basename,
              depth    = excluded.depth,
              root_id  = excluded.root_id,
              mtime    = excluded.mtime,
              segments = excluded.segments
            """;
        final Connection c = requireConn();
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Directory d : dirs) {
                ps.setString(1, d.basename);
                ps.setString(2, d.fullpath);
                ps.setInt(3, d.depth);
                ps.setInt(4, d.rootId);
                ps.setLong(5, d.mtimeEpochSeconds);
                if (d.lastUsedEpochSeconds == null) ps.setNull(6, Types.INTEGER);
                else ps.setLong(6, d.lastUsedEpochSeconds);
                ps.setString(7, d.segments);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }

    public boolean deleteDirectoryByFullpath(String fullpath) throws SQLException {
        final String sql = "DELETE FROM directories WHERE fullpath = ?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, fullpath);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Purge all directories belonging to a root (e.g., before a full reindex).
     */
    public int deleteDirectoriesByRootId(int rootId) throws SQLException {
        final String sql = "DELETE FROM directories WHERE root_id = ?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setInt(1, rootId);
            return ps.executeUpdate();
        }
    }

    public int deleteAllRootsAndDirectories(){
        final String sqlDirs = "DELETE FROM directories";
        final String sqlRoots = "DELETE FROM roots";
        try (Statement s = requireConn().createStatement()) {
            int deletedDirs = s.executeUpdate(sqlDirs);
            int deletedRoots = s.executeUpdate(sqlRoots);
            return deletedDirs + deletedRoots;
        } catch (SQLException e) {
            return 0;
        }

    }

    public Optional<Directory> getDirectoryByFullpath(String fullpath) throws SQLException {
        final String sql = """
            SELECT id, basename, fullpath, depth, root_id, mtime, last_used, segments
            FROM directories WHERE fullpath = ?
            """;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, fullpath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readDirectory(rs));
            }
        }
    }

    public List<Directory> findByBasename(String basename) throws SQLException {
        final String sql = """
            SELECT id, basename, fullpath, depth, root_id, mtime, last_used, segments
            FROM directories WHERE basename = ? COLLATE NOCASE
            """;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, basename);
            try (ResultSet rs = ps.executeQuery()) {
                return readDirectories(rs);
            }
        }
    }

    public List<Directory> findByBasenameAndRoot(String basename, int rootId) throws SQLException {
        final String sql = """
            SELECT id, basename, fullpath, depth, root_id, mtime, last_used, segments
            FROM directories WHERE basename = ? AND root_id = ?
            """;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, basename);
            ps.setInt(2, rootId);
            try (ResultSet rs = ps.executeQuery()) {
                return readDirectories(rs);
            }
        }
    }

    /**
     * Update last_used for MRU scoring (call after a successful jump).
     */
    public void touchLastUsed(int directoryId, long epochSeconds) throws SQLException {
        final String sql = "UPDATE directories SET last_used = ? WHERE id = ?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setLong(1, epochSeconds);
            ps.setInt(2, directoryId);
            ps.executeUpdate();
        }
    }

    // =========================================================================================
    // STATS / UTIL
    // =========================================================================================

    public long countDirectories() throws SQLException {
        try (PreparedStatement ps = requireConn().prepareStatement("SELECT COUNT(*) FROM directories");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public long countRoots() throws SQLException {
        try (PreparedStatement ps = requireConn().prepareStatement("SELECT COUNT(*) FROM roots");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * Vacuum occasionally (optional maintenance).
     */
    public void vacuum() throws SQLException {
        try (Statement s = requireConn().createStatement()) {
            s.execute("VACUUM");
        }
    }

    // === Internal row mappers ===

    private static Directory readDirectory(ResultSet rs) throws SQLException {
        return new Directory(
                rs.getInt("id"),
                rs.getString("basename"),
                rs.getString("fullpath"),
                rs.getInt("depth"),
                rs.getInt("root_id"),
                rs.getLong("mtime"),
                rs.getObject("last_used") == null ? null : rs.getLong("last_used"),
                rs.getString("segments")
        );
    }

    private static List<Directory> readDirectories(ResultSet rs) throws SQLException {
        List<Directory> list = new ArrayList<>();
        while (rs.next()) list.add(readDirectory(rs));
        return list;
    }

    // =========================================================================================
    // Convenience builder for Directory DTO (keeps call sites clean)
    // =========================================================================================

    public static Directory dirNew(
            String basename,
            String fullpath,
            int depth,
            int rootId,
            long mtimeEpochSeconds,
            String segments
    ) {
        return new Directory(
                0,
                basename,
                fullpath,
                depth,
                rootId,
                mtimeEpochSeconds,
                null,
                segments
        );
    }

    // Quick now() helper for MRU
    public static long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private void migrateLegacyPriorityColumn() throws SQLException {
        if (this.conn == null) {
            return;
        }
        boolean hasPriority = false;
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(roots)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final String columnName = rs.getString("name");
                if ("priority".equalsIgnoreCase(columnName)) {
                    hasPriority = true;
                    break;
                }
            }
        }
        if (!hasPriority) {
            return;
        }

        final boolean initialAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE roots RENAME TO roots_legacy_priority;");
            s.execute("""
                CREATE TABLE roots (
                  id       INTEGER PRIMARY KEY,
                  path     TEXT NOT NULL UNIQUE
                )
                """);
            s.execute("INSERT INTO roots (id, path) SELECT id, path FROM roots_legacy_priority;");
            s.execute("DROP TABLE roots_legacy_priority;");
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(initialAutoCommit);
        }
    }

    private static Path resolveDefaultDatabasePath() {
        String override = System.getenv("FLY_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).resolve("index.sqlite").toAbsolutePath();
        }
        String legacyOverride = System.getenv("CDF_DATA_DIR");
        if (legacyOverride != null && !legacyOverride.isBlank()) {
            return Path.of(legacyOverride).resolve("index.sqlite").toAbsolutePath();
        }
        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isBlank()) {
            Path candidate = Path.of(xdgData, "fly", "index.sqlite").toAbsolutePath();
            Path legacy = Path.of(xdgData, "cdf", "index.sqlite").toAbsolutePath();
            if (Files.notExists(candidate) && Files.exists(legacy)) {
                return legacy;
            }
            return candidate;
        }
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                Path candidate = Path.of(localAppData, "fly", "index.sqlite").toAbsolutePath();
                Path legacy = Path.of(localAppData, "cdf", "index.sqlite").toAbsolutePath();
                if (Files.notExists(candidate) && Files.exists(legacy)) {
                    return legacy;
                }
                return candidate;
            }
            String appDataFallback = System.getenv("APPDATA");
            if (appDataFallback != null && !appDataFallback.isBlank()) {
                Path candidate = Path.of(appDataFallback, "fly", "index.sqlite").toAbsolutePath();
                Path legacy = Path.of(appDataFallback, "cdf", "index.sqlite").toAbsolutePath();
                if (Files.notExists(candidate) && Files.exists(legacy)) {
                    return legacy;
                }
                return candidate;
            }
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return Path.of("index.sqlite").toAbsolutePath();
        }
        Path candidate = Path.of(home, ".local", "share", "fly", "index.sqlite").toAbsolutePath();
        Path legacy = Path.of(home, ".local", "share", "cdf", "index.sqlite").toAbsolutePath();
        if (Files.notExists(candidate) && Files.exists(legacy)) {
            return legacy;
        }
        return candidate;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    public List<Directory> findClosestByBasename(String basename) throws SQLException {
        // Fetch all directories with closest match on basename
        final String sql = """
            SELECT id, basename, fullpath, depth, root_id, mtime, last_used, segments
            FROM directories
            WHERE basename LIKE ? COLLATE NOCASE
            """;
        String pattern = "%" + basename + "%";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                return readDirectories(rs);
            }
        }
    }

    public Hashtable<String, String> getBaseNameAndPaths() throws SQLException {
        final String sql = "SELECT basename, fullpath FROM directories";
        Hashtable<String, String> table = new Hashtable<>();
        try (PreparedStatement ps = requireConn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                table.put(rs.getString("basename"), rs.getString("fullpath"));
            }
        }
        return table;
    }
}
