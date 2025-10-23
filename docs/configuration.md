# Configuration & Indexing Guide

The `fly` CLI keeps a small amount of state in two places:

| Location | Purpose |
|----------|---------|
| `~/.config/fly/.flyRoots` | Ordered list of registered root directories (one absolute path per line). Legacy `~/.config/cdf/.cdfRoots` files are migrated or still read. |
| `~/.config/fly/.flyIgnore` | Global ignore rules shared across every root (legacy `.cdfIgnore` is also honoured). |
| `<root>/.flyIgnore` | Optional per-root ignore rules (same syntax as the global file; `.cdfIgnore` remains supported). |
| `~/.local/share/fly/index.sqlite` | SQLite database storing the indexed directory metadata (override with `FLY_DATA_DIR`; legacy `cdf` locations remain compatible). |

All config paths can be overridden by setting `FLY_CONFIG_DIR`; otherwise the XDG config directory (or `$HOME/.config/fly`) is used. Legacy `CDF_CONFIG_DIR` continues to work as a fallback.

---

## 1. Roots File (`.flyRoots`)

`fly` reads the `~/.config/fly/.flyRoots` file on startup and synchronises it with the SQLite database. The file uses a simple format (legacy `.cdfRoots` files are still parsed transparently):

```
# fly roots file
# Format: <absolute-path>
/home/<user>/workspace
/home/<user>/playground
```

- Lines starting with `#` are comments and ignored.
- Paths are always stored as absolute paths; relative entries will be normalised.

Older releases prefixed each line with a numeric priority. Those lines continue to be parsed correctly; the numeric prefix is simply ignored when present. Saving the file through `--add-root` or by editing and re-running the CLI rewrites it using the new single-token format.

The CLI keeps the file sorted alphabetically when you run `flyctl --add-root ...`. You can safely edit the file manually—next launch of `flyctl` will sync it back into the database.

---

## 2. Ignore Files (`.flyIgnore`)

Ignore files adopt Git’s `gitignore` rules, with the following behaviour:

- Blank lines or comments (`# ...`) are ignored.
- A leading `!` negates a previous rule.
- Trailing `/` restricts the rule to directories.
- `*` matches characters within a path segment; `**` matches across segments.
- Leading `/` anchors the match to the root; otherwise the rule is matched anywhere within the path.

### 2.1 Global Ignore (`~/.config/fly/.flyIgnore`)

Applies to every indexed root. Example:

```
# global noise
.git/
node_modules/
target/
**/build/
```

### 2.2 Per-Root Ignore (`<root>/.flyIgnore`)

Place a `.flyIgnore` file inside a registered root to provide overrides specific to that root. Example (`/home/<user>/workspace/.flyIgnore`):

```
# Ignore generated docs in this repo only
docs/
!docs/.keep
```

Rules are evaluated in order: global file first, then root-specific. Negated rules (`!pattern`) can re-include directories previously ignored by a broader pattern. Existing `.cdfIgnore` files in either location are still respected.

---

## 3. Indexing Behaviour

- When you run `flyctl --reindex`, the tool wipes previously indexed paths for each root and walks the filesystem.
- Directories matching ignore rules are skipped entirely; their children are not traversed.
- Metadata for each directory (basename, full path, depth, timestamps) is written to `~/.local/share/fly/index.sqlite` (or the custom `FLY_DATA_DIR` location, with automatic fallback to legacy paths).
- Root entries in `.flyRoots` are normalised to absolute paths. If a root is removed from the file, deleting its row from `.flyRoots` manually and re-running `flyctl --reindex` will drop associated entries thanks to foreign-key cascade.
- To support numeric recall (`fly <index>`), the CLI stores the most recent query result list inside the SQLite database and overwrites it on the next lookup. Legacy data is reused if present.

---

## 4. Environment Overrides

| Variable | Description |
|----------|-------------|
| `FLY_CONFIG_DIR` | Override the location of `.flyRoots` and the global `.flyIgnore` (legacy `CDF_CONFIG_DIR` remains supported). |
| `FLY_DATA_DIR` | Override the base directory for the SQLite database (default `~/.local/share/fly`; legacy `CDF_DATA_DIR` remains supported). |
| `XDG_CONFIG_HOME` | Used automatically if set (e.g., on Linux/BSD systems). |
| JVM flag | Add `--enable-native-access=ALL-UNNAMED` when invoking `java` to silence JDK native-loading warnings from SQLite. |

If neither is set, `fly` falls back to `$HOME/.config/fly`, falling back to the legacy `cdf` paths if they already exist.

---

Keep this document aligned with code changes—especially if the config schema or ignore semantics evolve beyond the current MVP.
