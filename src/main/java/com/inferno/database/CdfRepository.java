package com.inferno.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain Java repository layer for cdf.
 * - Single embedded SQLite database (~/.local/share/cdf/index.sqlite by default)
 * - Creates schema on first run
 * - Exposes CRUD for roots and directories
 * - Provides batch upserts, deletions, basic queries, MRU updates, and stats
 *
 * Dependency: org.xerial:sqlite-jdbc (put it on the classpath)
 * JDK: 21+ (records used for DTOs)
 */
public final class CdfRepository implements AutoCloseable {

    // === DTOs (records keep it compact and immutable) ===
    public record Root(int id, String path, int priority) {}
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
          path     TEXT NOT NULL UNIQUE,
          priority INTEGER NOT NULL
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
     * Lower priority value == higher priority when ranking.
     */
    public int upsertRoot(String path, int priority) throws SQLException {
        Objects.requireNonNull(path);
        final String sql = """
            INSERT INTO roots(path, priority)
            VALUES(?, ?)
            ON CONFLICT(path) DO UPDATE SET priority = excluded.priority
            """;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, path);
            ps.setInt(2, priority);
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

    public List<Root> listRootsOrderByPriority() throws SQLException {
        final String sql = "SELECT id, path, priority FROM roots ORDER BY priority ASC, id ASC";
        try (PreparedStatement ps = requireConn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Root> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new Root(rs.getInt(1), rs.getString(2), rs.getInt(3)));
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
            FROM directories WHERE basename = ?
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

    private static Path resolveDefaultDatabasePath() {
        String override = System.getenv("CDF_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).resolve("index.sqlite").toAbsolutePath();
        }
        String xdgData = System.getenv("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isBlank()) {
            return Path.of(xdgData, "cdf", "index.sqlite").toAbsolutePath();
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return Path.of("index.sqlite").toAbsolutePath();
        }
        return Path.of(home, ".local", "share", "cdf", "index.sqlite").toAbsolutePath();
    }
}
