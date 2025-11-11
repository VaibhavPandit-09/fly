package com.inferno.logic;

import com.inferno.database.CdfRepository;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;

/**
 * Resolves jump queries against the indexed directory data.
 * v0.1 supports lookups by a single basename.
 */
public final class JumpPaths {
    private final CdfRepository repository;

    public JumpPaths(CdfRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    //Method to get paths by basename by closest spelling match of the basename. Get top 5 closest matches.
    public List<String> resolveByClosestBasename(String basename) throws SQLException {
        if (basename == null || basename.isBlank()) {
            return List.of();
        }

        Hashtable<String, String> allBasenames = repository.getBaseNameAndPaths();
        if (allBasenames.isEmpty()) {
            return List.of();
        } 
        Hashtable<String, Integer> distanceMap = new Hashtable<>();
        for (String existingBasename : allBasenames.keySet()) {
            int distance = calculateLevenshtein(basename, existingBasename);
            distanceMap.put(existingBasename, distance);
        }
        List<String> closestBasenames = distanceMap.entrySet().stream()
                .sorted(Comparator.comparingInt(java.util.Map.Entry::getValue))
                .limit(5)
                .map(java.util.Map.Entry::getKey)
                .toList();
        List<String> paths = closestBasenames.stream()
                .map(allBasenames::get)
                .toList();
        repository.replaceLastCallPaths(paths);
        return paths;

    }


    /**
     * Calculates the Levenshtein distance between two strings.
     * This is a standard dynamic programming implementation.
     */
    public static int calculateLevenshtein(String str1, String str2) {
        int[][] dp = new int[str1.length() + 1][str2.length() + 1];

        for (int i = 0; i <= str1.length(); i++) {
            for (int j = 0; j <= str2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j; // Cost of insertions
                } else if (j == 0) {
                    dp[i][j] = i; // Cost of deletions
                } else {
                    int cost = (str1.charAt(i - 1) == str2.charAt(j - 1)) ? 0 : 1; // Cost of substitution
                    
                    dp[i][j] = Math.min(
                        dp[i - 1][j] + 1,      // Deletion
                        Math.min(
                            dp[i][j - 1] + 1,  // Insertion
                            dp[i - 1][j - 1] + cost // Substitution
                        )
                    );
                }
            }
        }
        return dp[str1.length()][str2.length()];
    }

    /**
     * Resolve a single-token basename query to a directory path.
     */
    public List<String> resolveByBasename(String basename) throws SQLException {
        if (basename == null || basename.isBlank()) {
            return List.of();
        }

        List<CdfRepository.Directory> matches = repository.findByBasename(basename);
        if (matches.isEmpty()) {
            return List.of();
        }

        Comparator<CdfRepository.Directory> comparator = Comparator
                .comparingInt(CdfRepository.Directory::depth)
                .thenComparing(CdfRepository.Directory::fullpath);

        matches.sort(comparator);
        List<String> paths = matches.stream()
                .map(CdfRepository.Directory::fullpath)
                .toList();
        repository.replaceLastCallPaths(paths);
        return paths;
    }

     public List<String> getClosestPaths (String basename) throws SQLException {
        if (basename == null || basename.isBlank()) {
            return List.of();
        }

        List<CdfRepository.Directory> matches = repository.findClosestByBasename(basename);
        if (matches.isEmpty()) {
            return List.of();
        }

        Comparator<CdfRepository.Directory> comparator = Comparator
                .comparingInt(CdfRepository.Directory::depth)
                .thenComparing(CdfRepository.Directory::fullpath);

        matches.sort(comparator);
        List<String> paths = matches.stream()
                .map(CdfRepository.Directory::fullpath)
                .toList();
        repository.replaceLastCallPaths(paths);
        return paths;
    }

    // Take an string array. Last element is basename. All preceding elements are hints.
    public List<String> resolveByHintsAndBasename(String[] tokens) throws SQLException {
        if (tokens == null || tokens.length == 0) {
            return List.of();
        }
        String basename = tokens[tokens.length - 1];
        if (basename.isBlank()) {
            return List.of();
        }
        List<String> paths = resolveByBasename(basename);

        if (tokens.length == 1) {
            return paths;
        }
        // Apply hints filtering. Hints are to be found somewhere in the path. The order of hints does not matter.
        for (int i = 0; i < tokens.length - 1; i++) {
            String hint = tokens[i].toLowerCase(java.util.Locale.ROOT);
            if (hint.isBlank()) {
                continue;
            }
            paths = paths.stream()
                    .filter(path -> path.toLowerCase(java.util.Locale.ROOT).contains(hint))
                    .toList();
            if (paths.isEmpty()) {
                break;
            }
        }
        if(paths.size() == 1) {
            return paths;
        }
        repository.replaceLastCallPaths(paths);
        return paths;
    
    }

    // Get a path from the last query by its index (1-based)
    public List<String> getPathFromLastCall(int index) {
        List<String> paths;
        try {
            paths = repository.getLastCallPaths();
        } catch (SQLException e) {
            return List.of();
        }
        if (index < 1 || index > paths.size()) {
            return List.of();
        }
        return List.of(paths.get(index - 1));
    }




}
