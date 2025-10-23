# fly — Deterministic Directory Navigator

`fly` is a command-line helper that lets you jump to any indexed directory deterministically. Instead of guessing based on usage frequency, `fly` matches directory basenames (and eventually ordered tokens) against a curated index that you control. Existing `cdf` installs continue to work; legacy paths are detected automatically.

---

## Current Capabilities (v0.1)

- **SQLite-backed index** stored at `~/.local/share/fly/index.sqlite` (override with `FLY_DATA_DIR`; legacy `CDF_DATA_DIR` and locations are honoured).
- **Root management** via `--add-root` and `--list-roots`.
- **Full reindex** over all configured roots with `.flyIgnore` support (global + per-root gitignore syntax, legacy `.cdfIgnore` still read).
- **Single-token jumps** ranked by directory depth, with numbered disambiguation.
- **Quick re-selection**: rerun `fly <index>` to emit a path from the last multi-match.
- **Silent runtime**: embedded SLF4J NOP binder, native loading disabled (no noisy warnings).
- **Shaded CLI artifact** (`cdf-1.0-SNAPSHOT-all.jar`) bundles SQLite and logging dependencies.

See `Changelog.md` for a running log of fixes and decisions.

---

## Install & Build

| Step | Command / Notes |
|------|-----------------|
| Prerequisites | JDK 25, Maven 3.9+. |
| Build | `mvn clean package` (produces `target/cdf-1.0-SNAPSHOT-all.jar`). |
| Deploy | `sudo cp target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/flyctl-all.jar` |
| Shell function | Append to `~/.bashrc` or `~/.zshrc`:<br/>```bash<br/>fly() {<br/>  local target<br/>  target=$(java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar "$@")<br/>  if [ $? -eq 0 ] && [ -n "$target" ]; then<br/>    cd "$target" || return<br/>  fi<br/>}<br/>``` |
| Docs | `Build.md` (setup), `docs/configuration.md` (roots, ignores, env overrides). |

---

## Usage Cheatsheet

```bash
# Register roots
java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --add-root ~/workspace
java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --add-root ~/playground

# Rebuild the index (global + per-root ignore respected)
java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --reindex

# Count indexed directories
java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --count

# Jump by basename
fly services

# Reuse a numbered match from the previous query
fly 2

# List configured roots
java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --list-roots

# Reset database and roots (drops everything)
java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --reset
```

### Configuration Files

- `~/.config/fly/.flyRoots` — Machine-readable root list (one absolute path per line). Legacy `~/.config/cdf/.cdfRoots` will be migrated on demand.
- `~/.config/fly/.flyIgnore` — Global gitignore-style rules (legacy `.cdfIgnore` is still honoured if present).
- `<root>/.flyIgnore` — Per-root overrides, merged after global (`.cdfIgnore` files are also read for compatibility).

Environment overrides:

| Variable | Purpose |
|----------|---------|
| `FLY_CONFIG_DIR` | Relocate `.flyRoots` / global `.flyIgnore`. (`CDF_CONFIG_DIR` continues to work as a fallback.) |
| `FLY_DATA_DIR` | Relocate the SQLite store. (`CDF_DATA_DIR` remains supported.) |
| `XDG_CONFIG_HOME`, `XDG_DATA_HOME` | Honoured automatically if set. |

---

## Testing & Diagnostics

- `java --enable-native-access=ALL-UNNAMED -jar ... --reindex` doubles as an integrity check (schema is re-created if missing).
- `java --enable-native-access=ALL-UNNAMED -jar ... --list-roots` confirms configuration sync between files and SQLite.
- `Changelog.md` captures known issues and fixes (e.g., packaging, logging noise).

SQLite WAL files (`index.sqlite-wal`, `index.sqlite-shm`) live alongside the primary database. Safe to delete when the CLI is not running; a reindex will rebuild from scratch.

---

## Roadmap

| Milestone | Status | Highlights |
|-----------|--------|------------|
| **v0.1** | ✅ | Basename jump, SQLite storage, `.flyIgnore`, shaded packaging, silent logging. |
| **v0.2** | 🚧 | Multi-token ordered search (query like `fly project api`). |
| **v0.3** | 📌 | Incremental refresh (`--refresh`), directory change detection. |
| **v0.4** | 📌 | MRU ranking, richer list output, additional ranking heuristics. |
| **v0.5** | 📌 | Interactive picker (fzf fallback), stats/diagnostics commands. |
| **v1.0** | 📌 | Cross-platform polish, installers, scheduled reindex integration. |

Ideas under evaluation: cached query suggestions, optional fuzzy fallback, integration with shell completion frameworks.

---

## Contributing / Next Steps

- Keep `Changelog.md`, `Build.md`, and `docs/configuration.md` in sync when behaviours change.
- Prefer shaded builds (`*-all.jar`) for distribution; ensures JDBC driver availability.
- Open issues for feature requests or divergence between documentation and behaviour. A short repro (`--list-roots`, `--reindex`, sample `.flyRoots`) helps a ton.

Happy deterministic hopping!
