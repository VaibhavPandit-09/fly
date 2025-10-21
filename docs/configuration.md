# Configuration & Indexing Guide

The `cdf` CLI keeps a small amount of state in two places:

| Location | Purpose |
|----------|---------|
| `~/.config/cdf/.cdfRoots` | Ordered list of registered root directories along with their priorities. |
| `~/.config/cdf/.cdfIgnore` | Global ignore rules shared across every root. |
| `<root>/.cdfIgnore` | Optional per-root ignore rules (same syntax as the global file). |
| `~/.local/share/cdf/index.sqlite` | SQLite database storing the indexed directory metadata (override with `CDF_DATA_DIR`). |

All config paths can be overridden by setting `CDF_CONFIG_DIR`; otherwise the XDG config directory (or `$HOME/.config/cdf`) is used.

---

## 1. Roots File (`.cdfRoots`)

`cdf` reads the `~/.config/cdf/.cdfRoots` file on startup and synchronises it with the SQLite database. The file uses a simple format:

```
# cdf roots file
# Format: <priority> <absolute-path>
10 /home/<user>/workspace
20 /home/<user>/playground
```

- **Priority**: lower numbers win when multiple roots contain the same basename.
- Lines starting with `#` are comments and ignored.
- Paths are always stored as absolute paths; relative entries will be normalised.

The CLI keeps the file sorted by priority when you run `cdfctl --add-root ...`. You can safely edit the file manually—next launch of `cdfctl` will sync it back into the database.

---

## 2. Ignore Files (`.cdfIgnore`)

Ignore files adopt Git’s `gitignore` rules, with the following behaviour:

- Blank lines or comments (`# ...`) are ignored.
- A leading `!` negates a previous rule.
- Trailing `/` restricts the rule to directories.
- `*` matches characters within a path segment; `**` matches across segments.
- Leading `/` anchors the match to the root; otherwise the rule is matched anywhere within the path.

### 2.1 Global Ignore (`~/.config/cdf/.cdfIgnore`)

Applies to every indexed root. Example:

```
# global noise
.git/
node_modules/
target/
**/build/
```

### 2.2 Per-Root Ignore (`<root>/.cdfIgnore`)

Place a `.cdfIgnore` file inside a registered root to provide overrides specific to that root. Example (`/home/<user>/workspace/.cdfIgnore`):

```
# Ignore generated docs in this repo only
docs/
!docs/.keep
```

Rules are evaluated in order: global file first, then root-specific. Negated rules (`!pattern`) can re-include directories previously ignored by a broader pattern.

---

## 3. Indexing Behaviour

- When you run `cdfctl --reindex`, the tool wipes previously indexed paths for each root and walks the filesystem.
- Directories matching ignore rules are skipped entirely; their children are not traversed.
- Metadata for each directory (basename, full path, depth, timestamps) is written to `~/.local/share/cdf/index.sqlite` (or the custom `CDF_DATA_DIR` location).
- Root entries in `.cdfRoots` are normalised to absolute paths. If a root is removed from the file, deleting its row from `.cdfRoots` manually and re-running `cdfctl --reindex` will drop associated entries thanks to foreign-key cascade.

---

## 4. Environment Overrides

| Variable | Description |
|----------|-------------|
| `CDF_CONFIG_DIR` | Override the location of `.cdfRoots` and the global `.cdfIgnore`. |
| `CDF_DATA_DIR` | Override the base directory for the SQLite database (default `~/.local/share/cdf`). |
| `XDG_CONFIG_HOME` | Used automatically if set (e.g., on Linux/BSD systems). |
| JVM flag | Add `--enable-native-access=ALL-UNNAMED` when invoking `java` to silence JDK native-loading warnings from SQLite. |

If neither is set, `cdf` falls back to `$HOME/.config/cdf`.

---

Keep this document aligned with code changes—especially if the config schema or ignore semantics evolve beyond the current MVP.
