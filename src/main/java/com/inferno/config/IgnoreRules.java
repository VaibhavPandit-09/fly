package com.inferno.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Minimal gitignore-style matcher for directories.
 * Supports:
 * - Blank lines and comments starting with '#'
 * - Negation with leading '!'
 * - Trailing '/' to mark directory-only rules
 * - Leading '/' to anchor to the root
 * - Wildcards: '*', '**', '?'
 */
public final class IgnoreRules {
    private static final IgnoreRules EMPTY = new IgnoreRules(List.of());

    private final List<Rule> rules;

    private IgnoreRules(List<Rule> rules) {
        this.rules = Collections.unmodifiableList(rules);
    }

    public static IgnoreRules empty() {
        return EMPTY;
    }

    public static IgnoreRules compile(List<String> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return empty();
        }
        List<Rule> compiled = new ArrayList<>();
        for (String raw : rawPatterns) {
            Rule rule = Rule.fromRaw(raw);
            if (rule != null) {
                compiled.add(rule);
            }
        }
        if (compiled.isEmpty()) {
            return empty();
        }
        return new IgnoreRules(compiled);
    }

    /**
     * Returns true when the relative path under root should be ignored.
     */
    public boolean shouldIgnore(Path root, Path candidate, boolean isDirectory) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(candidate, "candidate");
        if (root.equals(candidate)) {
            return false;
        }

        Path relative;
        try {
            relative = root.relativize(candidate);
        } catch (IllegalArgumentException e) {
            // Candidate outside root; do not ignore here.
            return false;
        }

        String rel = relative.toString().replace('\\', '/');
        if (rel.isEmpty()) {
            return false;
        }

        boolean ignored = false;
        for (Rule rule : rules) {
            if (rule.directoryOnly && !isDirectory) {
                continue;
            }
            if (rule.matches(rel)) {
                ignored = !rule.negated;
            }
        }
        return ignored;
    }

    private record Rule(Pattern pattern, boolean negated, boolean directoryOnly) {
        static Rule fromRaw(String rawLine) {
            if (rawLine == null) return null;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                return null;
            }

            boolean negated = false;
            if (line.startsWith("!")) {
                negated = true;
                line = line.substring(1).trim();
            }
            if (line.isEmpty()) {
                return null;
            }

            boolean directoryOnly = false;
            if (line.endsWith("/")) {
                directoryOnly = true;
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                return null;
            }

            boolean anchored = line.startsWith("/");
            if (anchored) {
                line = line.substring(1);
            }

            line = line.replace('\\', '/');
            if (line.isEmpty()) {
                return null;
            }

            String effectivePattern = line;
            if (!anchored && !effectivePattern.startsWith("**/")) {
                effectivePattern = "**/" + effectivePattern;
            }

            String regex = toRegex(effectivePattern);
            Pattern compiled = Pattern.compile(regex);
            return new Rule(compiled, negated, directoryOnly);
        }

        boolean matches(String relativePath) {
            return pattern.matcher(relativePath).matches();
        }

        private static String toRegex(String glob) {
            StringBuilder sb = new StringBuilder();
            sb.append('^');
            int i = 0;
            while (i < glob.length()) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*':
                        if ((i + 1) < glob.length() && glob.charAt(i + 1) == '*') {
                            i += 2;
                            boolean hasSlash = i < glob.length() && glob.charAt(i) == '/';
                            if (hasSlash) {
                                sb.append("(?:.*/)?");
                                i++;
                            } else {
                                sb.append(".*");
                            }
                        } else {
                            sb.append("[^/]*");
                            i++;
                        }
                        break;
                    case '?':
                        sb.append("[^/]");
                        i++;
                        break;
                    case '.': case '(': case ')': case '+': case '|':
                    case '^': case '$': case '@': case '%': case '{':
                    case '}': case '[': case ']': case '\\':
                        sb.append('\\').append(c);
                        i++;
                        break;
                    default:
                        sb.append(c);
                        i++;
                        break;
                }
            }
            sb.append('$');
            return sb.toString();
        }
    }
}
