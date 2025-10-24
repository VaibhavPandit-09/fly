package com.inferno.cli;

import com.inferno.config.ConfigManager;
import com.inferno.database.CdfRepository;
import com.inferno.logic.DirectoryIndexer;
import com.inferno.logic.JumpPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Minimal CLI surface for v0.1:
 * - --add-root <path>
 * - --reindex
 * - --list-roots
 * - --count
 * - --reset
 * - <basename>
 */
public final class FlyCli {
    private final CdfRepository repository;
    private final ConfigManager configManager;
    private final DirectoryIndexer indexer;
    private final JumpPaths jumpPaths;

    public FlyCli(CdfRepository repository, ConfigManager configManager) {
        this.repository = Objects.requireNonNull(repository);
        this.configManager = Objects.requireNonNull(configManager);
        this.indexer = new DirectoryIndexer(repository, configManager);
        this.jumpPaths = new JumpPaths(repository);
    }

    public int execute(String[] args) throws SQLException, IOException {
        if (args.length == 0) {
            printUsage();
            return 1;
        }

        final String command = args[0];
        return switch (command) {
            case "--help", "-h" -> {
                printUsage();
                yield 0;
            }
            case "--add-root" -> handleAddRoot(args);
            case "--list-roots" -> handleListRoots();
            case "--count" -> handlePathCount();
            case "--reindex" -> handleReindex();
            case "--reset"  -> handleDatabaseReset();
            default -> handleBasenameQuery(args);
        };
    }

    private int handleDatabaseReset() {
        int deleted = repository.deleteAllRootsAndDirectories();
        System.out.printf("Reset complete; removed %,d indexed entries.%n", deleted);
        return 0;
    }

    private int handlePathCount() {
        Long n;
        try {
            n = repository.countDirectories();
            System.out.printf("Total indexed directories: %,d%n", n);
        } catch (SQLException e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }
    

    private void printUsage() {
        System.out.println("""
                Usage:
                  flyctl --add-root <path>      Add or update a root
                  flyctl --list-roots           Show configured roots
                  flyctl --reindex              Rebuild directory index
                  flyctl --count                Print total indexed directories
                  flyctl --reset                Drop all roots and indexed directories
                  fly <basename|index>          Print path for basename or reuse numbered match
                """.stripTrailing());
    }

    private int handleAddRoot(String[] args) throws SQLException, IOException {
        if (args.length < 2) {
            System.err.println("Missing path for --add-root");
            return 1;
        }
        final Path rootPath = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            System.err.printf("Path '%s' is not a directory.%n", rootPath);
            return 1;
        }

        if (args.length != 2) {
            System.err.println("Too many arguments for --add-root.");
            return 1;
        }

        configManager.addOrUpdateRoot(rootPath);
        repository.upsertRoot(rootPath.toString());
        System.out.printf("Root registered: %s%n", rootPath);
        return 0;
    }

    private int handleListRoots() throws SQLException {
        List<CdfRepository.Root> roots = repository.listRoots();
        if (roots.isEmpty()) {
            System.out.println("No roots configured.");
            return 0;
        }
        System.out.println("Config directory: " +configManager.configDir());
        for (CdfRepository.Root root : roots) {
            System.out.println(root.path());
        }
        return 0;
    }

    private int handleReindex() throws SQLException, IOException {
        List<CdfRepository.Root> roots = repository.listRoots();
        if (roots.isEmpty()) {
            System.err.println("No roots configured. Add one with --add-root first.");
            return 1;
        }
        indexer.reindexAllRoots();
        return 0;
    }

    private int handleBasenameQuery(String[] args) throws SQLException {

        if (args.length > 1) {
            List<String> pathFromHints = jumpPaths.resolveByHintsAndBasename(args);
            if (pathFromHints.isEmpty()) {
                System.err.printf("No directory indexed matching hints and basename '%s'.%n", args[args.length - 1]);
                return 1;
            }
            if (pathFromHints.size() == 1) {
                System.out.println(pathFromHints.get(0));
            } else {
                System.out.println("--Multiple matches found--");
                for (int i = 0; i < pathFromHints.size(); i++) {
                    System.out.println((i + 1) + ": " + pathFromHints.get(i));
                }
            }
            return 0;
        }
        

        //Check if argument is an index from last call
        try {
            int index = Integer.parseInt(args[0]);
            List<String> pathFromIndex = jumpPaths.getPathFromLastCall(index);
            if (!pathFromIndex.isEmpty()) {
                System.out.println(pathFromIndex.get(0));
                return 0;
            }
        } catch (NumberFormatException ignore) {
            // Fall through to basename lookup.
        }

        List<String> match = jumpPaths.resolveByBasename(args[0]);
        if (match.isEmpty()) {
            System.err.printf("No directory indexed with basename '%s'.%n", args[0]);
            return 1;
        }

        if (match.size() == 1) {
            System.out.println(match.get(0));
        } else {
            System.out.println("--Multiple matches found--");
            for (int i = 0; i < match.size(); i++) {
                System.out.println((i + 1) + ": " + match.get(i));
            }
        }
        return 0;
    }
}
