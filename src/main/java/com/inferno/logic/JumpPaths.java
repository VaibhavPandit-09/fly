package com.inferno.logic;

import com.inferno.database.CdfRepository;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    public Optional<CdfRepository.Directory> resolveByBasename(String basename) throws SQLException {
        if (basename == null || basename.isBlank()) {
            return Optional.empty();
        }

        List<CdfRepository.Directory> matches = repository.findByBasename(basename);
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        Map<Integer, CdfRepository.Root> rootsById = new HashMap<>();
        for (CdfRepository.Root root : repository.listRootsOrderByPriority()) {
            rootsById.put(root.id(), root);
        }

        Comparator<CdfRepository.Directory> comparator = Comparator
                .comparingInt((CdfRepository.Directory d) -> rootPriority(d.rootId(), rootsById))
                .thenComparingInt(CdfRepository.Directory::depth)
                .thenComparing(CdfRepository.Directory::fullpath);

        matches.sort(comparator);
        return Optional.of(matches.get(0));
    }

    private static int rootPriority(int rootId, Map<Integer, CdfRepository.Root> roots) {
        CdfRepository.Root root = roots.get(rootId);
        return root != null ? root.priority() : Integer.MAX_VALUE;
    }
}
