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
import java.util.Optional;

/**
 * Minimal CLI surface for v0.1:
 * - --add-root <path> [--priority <int>]
 * - --reindex
 * - --list-roots
 * - <basename>
 */
public final class CdfCli {
    private static final int DEFAULT_PRIORITY = 100;

    private final CdfRepository repository;
    private final ConfigManager configManager;
    private final DirectoryIndexer indexer;
    private final JumpPaths jumpPaths;

    public CdfCli(CdfRepository repository, ConfigManager configManager) {
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
            case "--list-r" -> handleListRoots();
            case "--count" -> handlePathCount();
            case "--reindex" -> handleReindex();
            default -> handleBasenameQuery(args);
        };
    }

    private int handlePathCount() {
        Long n;
        try {
            n = repository.countDirectories();
            System.out.println("Total number of indexed paths : "+n);
        } catch (SQLException e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }
    

    private void printUsage() {
        System.out.println("""
                Usage:
                  cdfctl --add-root <path> [--priority <int>]   Add or update a root
                  cdfctl --list-roots                          Show configured roots
                  cdfctl --reindex                             Rebuild directory index
                  cdfctl <basename>                            Print path for basename
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

        int priority = DEFAULT_PRIORITY;
        if (args.length == 4) {
            if (!"--priority".equals(args[2])) {
                System.err.println("Expected --priority <int> after path.");
                return 1;
            }
            try {
                priority = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                System.err.printf("Invalid priority '%s'.%n", args[3]);
                return 1;
            }
        } else if (args.length != 2) {
            System.err.println("Too many arguments for --add-root.");
            return 1;
        }

        configManager.addOrUpdateRoot(rootPath, priority);
        repository.upsertRoot(rootPath.toString(), priority);
        System.out.printf("Root registered: %s (priority %d)%n", rootPath, priority);
        return 0;
    }

    private int handleListRoots() throws SQLException {
        List<CdfRepository.Root> roots = repository.listRootsOrderByPriority();
        if (roots.isEmpty()) {
            System.out.println("No roots configured.");
            return 0;
        }
        System.out.println("Config directory: " +configManager.configDir());
        for (CdfRepository.Root root : roots) {
            System.out.println("priority="+root.id()+" path="+root.path());
        }
        return 0;
    }

    private int handleReindex() throws SQLException, IOException {
        List<CdfRepository.Root> roots = repository.listRootsOrderByPriority();
        if (roots.isEmpty()) {
            System.err.println("No roots configured. Add one with --add-root first.");
            return 1;
        }
        indexer.reindexAllRoots();
        return 0;
    }

    private int handleBasenameQuery(String[] args) throws SQLException {
        if (args.length != 1) {
            System.err.println("v0.1 only supports single-token basename queries.");
            return 1;
        }

        Optional<CdfRepository.Directory> match = jumpPaths.resolveByBasename(args[0]);
        if (match.isEmpty()) {
            System.err.printf("No directory indexed with basename '%s'.%n", args[0]);
            return 1;
        }

        System.out.println(match.get().fullpath());
        return 0;
    }
}
