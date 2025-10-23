package com.inferno.logic;

import com.inferno.config.ConfigManager;
import com.inferno.config.IgnoreRules;
import com.inferno.database.CdfRepository;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Responsible for scanning configured roots and materialising directory entries in SQLite.
 * MVP implementation: full wipe + rebuild per root, now honoring .cdfIgnore patterns.
 */
public final class DirectoryIndexer {
    private final CdfRepository repository;
    private final ConfigManager configManager;

    public DirectoryIndexer(CdfRepository repository, ConfigManager configManager) {
        this.repository = Objects.requireNonNull(repository);
        this.configManager = Objects.requireNonNull(configManager);
    }

    /**
     * Reindex every configured root.
     */
    public void reindexAllRoots() throws SQLException, IOException {
        for (CdfRepository.Root root : repository.listRoots()) {
            reindexRoot(root);
        }
    }

    /**
     * Wipes and rebuilds the directory index for a single root.
     */
    public void reindexRoot(CdfRepository.Root root) throws SQLException, IOException {
        Objects.requireNonNull(root, "root");
        final Path rootPath = Path.of(root.path()).toAbsolutePath().normalize();

        if (!Files.isDirectory(rootPath)) {
            System.err.printf("Skipping root '%s' — not a directory or missing.%n", root.path());
            repository.deleteDirectoriesByRootId(root.id());
            return;
        }

        repository.deleteDirectoriesByRootId(root.id());
        final List<CdfRepository.Directory> batch = new ArrayList<>();
        final IgnoreRules ignoreRules = configManager.buildIgnoreRulesForRoot(rootPath);

        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!rootPath.equals(dir) && ignoreRules.shouldIgnore(rootPath, dir, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    batch.add(toDirectoryRecord(root, rootPath, dir));
                } catch (IOException e) {
                    System.err.printf("Failed to read metadata for %s: %s%n", dir, e.getMessage());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.printf("Failed to access %s: %s%n", file, exc.getMessage());
                return FileVisitResult.SKIP_SUBTREE;
            }
        };

        Files.walkFileTree(rootPath, visitor);

        repository.batchUpsertDirectories(batch);
        System.out.printf("Indexed %,d directories under %s%n", batch.size(), rootPath);
    }

    private static CdfRepository.Directory toDirectoryRecord(CdfRepository.Root root,
                                                             Path rootPath,
                                                             Path dirPath) throws IOException {
        final Path absoluteDir = dirPath.toAbsolutePath().normalize();
        final String basename = absoluteDir.getFileName() == null
                ? absoluteDir.toString()
                : absoluteDir.getFileName().toString();
        int depth;
        try {
            depth = rootPath.relativize(absoluteDir).getNameCount();
        } catch (IllegalArgumentException e) {
            // Should not happen if absoluteDir is under rootPath, but fall back to absolute depth.
            depth = absoluteDir.getNameCount();
        }
        final long mtime = Files.getLastModifiedTime(absoluteDir).toInstant().getEpochSecond();
        final String segments = absoluteDir.toString().replace('\\', '/');

        return CdfRepository.dirNew(
                basename,
                absoluteDir.toString(),
                depth,
                root.id(),
                mtime,
                segments
        );
    }
}
