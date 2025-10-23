package com.inferno.logic;

import com.inferno.database.CdfRepository;

import java.sql.SQLException;
import java.util.Comparator;
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
