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
import java.util.Scanner;

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
            case "--version" -> {
                System.out.println("fly version 1.0.7");
                yield 0;
            }
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
                  fly --add-root <path>      Add or update a root
                  fly --list-roots           Show configured roots
                  fly --reindex              Rebuild directory index
                  fly --count                Print total indexed directories
                  fly --reset                Drop all roots and indexed directories
                  fly <basename|index>       Print path for basename or reuse numbered match
                  fly <hints> <basename>     Print path for basename with hints
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

    /**
 * Handles the main logic for the basename query.
 * It determines whether hints are provided and calls the appropriate search.
 * It then delegates the handling of the results (empty, single, or multiple)
 * to the processMatches helper method.
 *
 * @param args Command-line arguments.
 * @return 0 on success, 1 on error.
 * @throws SQLException If a database error occurs.
 */
private int handleBasenameQuery(String[] args) throws SQLException {

    Scanner scanner = new Scanner(System.in);
    List<String> matches;

    if (args.length > 1) {
        // --- Path 1: Query WITH hints ---
        // The last arg is basename, preceding are hints.
        matches = jumpPaths.resolveByHintsAndBasename(args);

        if (matches.isEmpty()) {
            System.err.printf("No directory indexed matching hints and basename '%s'.%n", args[args.length - 1]);
            return 1;
        }
        
    } else {
        // --- Path 2: Query WITHOUT hints (just basename) ---
        // First, try the "closest" search.
        matches = jumpPaths.getClosestPaths(args[0]);

        if (matches.isEmpty()) {
            // If "closest" fails, print an error and try the "resolve" fallback.
            System.err.printf("No directory indexed with basename '%s'.%n", args[0]);
            
            matches = jumpPaths.resolveByClosestBasename(args[0]);
            
            if (matches.isEmpty()) {
                // Both primary and fallback searches failed.
                return 1;
            }
        }
    }

    // At this point, we have a non-empty list of matches.
    // Pass them to the helper to handle the 1-vs-many logic.
    return processMatches(matches, scanner);
}

/**
 * Helper method to process a non-empty list of matches.
 * - If 1 match, it prints it.
 * - If >1 match, it prompts the user to select one.
 *
 * @param matches The non-empty list of path matches.
 * @param scanner A Scanner instance to read user input.
 * @return 0 on success (path printed), 1 on user error (invalid choice).
 * @throws SQLException If a database error occurs.
 */
private int processMatches(List<String> matches, Scanner scanner) throws SQLException {
    
    // Case 1: Exactly one match. Print it and exit successfully.
    if (matches.size() == 1) {
        System.out.println(matches.get(0));
        return 0;
    }

    // Case 2: Multiple matches. Print them all and prompt for a choice.
    System.err.println("--Multiple matches found--");
    for (int i = 0; i < matches.size(); i++) {
        System.err.println((i + 1) + ": " + matches.get(i));
    }

    System.err.print("Select index of a path to fly: ");
    String line = scanner.nextLine();

    try {
        int choice = Integer.parseInt(line);
        if (choice < 1 || choice > matches.size()) {
            System.err.println("Invalid choice.");
            return 1;
        }
        
        // Assumes 'jumpPaths' is a member variable
        System.out.println(jumpPaths.getPathFromLastCall(choice).get(0));
        return 0;

    } catch (NumberFormatException e) {
        System.err.println("Invalid input.");
        return 1;
    }
}
}
