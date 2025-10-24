# Configuration & Indexing Reference

This document dives into how `fly` stores configuration, honours ignore rules, and remains compatible with legacy `cdf` layouts. Refer back to `ReadMe.md` for installation instructions and quick usage tips.

---

## Layout Overview

`fly` keeps two kinds of state: configuration files (roots + ignore rules) and SQLite data. Defaults differ slightly by platform but follow the same structure.

| Purpose | Linux | macOS | Windows | Notes |
|---------|-------|-------|---------|-------|
| Config directory | `~/.config/fly` | `~/.config/fly` (or custom) | `%APPDATA%\fly` | Holds `.flyRoots` and `.flyIgnore`. |
| Data directory | `~/.local/share/fly` | `~/.local/share/fly` (set `FLY_DATA_DIR` to use `~/Library/Application Support/fly`) | `%LOCALAPPDATA%\fly` | Stores `index.sqlite` and WAL companions. |
| Per-root overrides | `<root>/.flyIgnore` | `<root>/.flyIgnore` | `<root>/.flyIgnore` | Gitignore semantics; `.cdfIgnore` still honoured. |

The directories above are created on first run by `ConfigManager.ensureLayout()` and `CdfRepository.open()`. You may point them elsewhere with environment variables (see below).

---

## Roots File (`.flyRoots`)

- Location: `<config-dir>/.flyRoots`.
- Format: one absolute path per line; comments begin with `#`.
- Legacy support: `.cdfRoots` files (with optional numeric prefixes) are parsed transparently.

Example:

```
# fly roots file
# Format: <absolute-path>
/home/alex/workspace
/srv/repos
```

`flyctl --add-root` normalises and sorts the entries when writing the file. Manual edits are fine; the next CLI invocation syncs the contents to SQLite.

---

## Ignore Rules (`.flyIgnore`)

Ignore files follow Git’s syntax:

- Blank lines and comments (`#`) are ignored.
- Trailing `/` limits the rule to directories.
- `*` matches characters inside a segment; `**` crosses segment boundaries.
- `!` negates a previous match.
- Leading `/` anchors the pattern to the start of the path.

### Precedence

1. Global file: `<config-dir>/.flyIgnore`.
2. Per-root file: `<root>/.flyIgnore`.
3. Legacy equivalents: `.cdfIgnore` in either location.

Rules are merged in that order. Later matches (including negations) override earlier ones.

Example global file (Linux/macOS path):

```
# shared noise
.git/
node_modules/
**/build/
```

Per-root override:

```
# keep docs in this repo
docs/
!docs/.keep
```

---

## Environment Overrides

| Variable | Scope | Example (Linux/macOS) | Example (Windows) |
|----------|-------|-----------------------|-------------------|
| `FLY_CONFIG_DIR` | Entire config directory | `export FLY_CONFIG_DIR="$HOME/Library/Application Support/fly/config"` | `$env:FLY_CONFIG_DIR = "$env:APPDATA\fly-config"` |
| `FLY_DATA_DIR` | SQLite directory | `export FLY_DATA_DIR="$HOME/Library/Application Support/fly/data"` | `$env:FLY_DATA_DIR = "$env:LOCALAPPDATA\fly-data"` |
| `CDF_CONFIG_DIR` | Legacy override | `export CDF_CONFIG_DIR="$HOME/.cdf-config"` | `$env:CDF_CONFIG_DIR = "$env:APPDATA\cdf"` |
| `CDF_DATA_DIR` | Legacy override | `export CDF_DATA_DIR="$HOME/.cdf-data"` | `$env:CDF_DATA_DIR = "$env:LOCALAPPDATA\cdf"` |
| `XDG_CONFIG_HOME` | Linux/macOS XDG | `export XDG_CONFIG_HOME="$HOME/.config"` | n/a |
| `XDG_DATA_HOME` | Linux/macOS XDG | `export XDG_DATA_HOME="$HOME/.local/share"` | n/a |

Resolution order:

1. Explicit `FLY_*` overrides.
2. Legacy `CDF_*` overrides (for backwards compatibility).
3. XDG variables (Linux/macOS).
4. Platform defaults (Windows: `%APPDATA%` / `%LOCALAPPDATA%`; Linux/macOS: `~/.config`, `~/.local/share`).

Set overrides before launching the CLI (e.g., export them in your shell profile).

---

## Legacy `cdf` Compatibility

`fly` automatically reuses existing `cdf` assets when new paths are absent:

- If `.flyRoots` is missing but `.cdfRoots` exists, the file is copied on first run.
- If `.flyIgnore` is missing, any `.cdfIgnore` file becomes the initial source.
- When no `fly/index.sqlite` is present, but a legacy `cdf/index.sqlite` exists, the repository points to the legacy database until a new file is created.
- Environment variables `CDF_CONFIG_DIR` and `CDF_DATA_DIR` still function. Prefer the new `FLY_*` variables for clean setups.

This behaviour makes upgrades transparent for existing users.

---

## Inspecting and Maintaining the Index

- **Inspect schema**
  ```bash
  sqlite3 ~/.local/share/fly/index.sqlite ".tables"
  sqlite3 ~/.local/share/fly/index.sqlite "PRAGMA table_info(directories);"
  ```
- **Manual cleanup** – Remove `index.sqlite`, `index.sqlite-wal`, and `index.sqlite-shm` when the CLI is not running; the next `--reindex` rebuilds everything.
- **Integrity check** – Running `flyctl --reindex` automatically recreates missing schema objects and ensures referential integrity.

---

## Windows & macOS Tips

- **macOS Application Support** – Set `FLY_CONFIG_DIR` / `FLY_DATA_DIR` to subdirectories of `~/Library/Application Support/fly` for a more native layout. Create the directories before invoking the CLI.
- **macOS symlinks** – If you prefer dotfiles under `~/.config`, but want Finder-visible state, symlink `~/Library/Application Support/fly` to `~/.config/fly`.
- **Windows roaming profiles** – Keep config under `%APPDATA%` (roaming) and data under `%LOCALAPPDATA%` (machine-specific) as per defaults. This avoids large SQLite files being synced across profiles.
- **PowerShell scripts** – When automating reindexing, use:
  ```powershell
  java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\flyctl-all.jar --reindex
  ```
  Run from `Task Scheduler` with “Run whether user is logged on or not” for background maintenance.

---

## Advanced Ignore Techniques

- **Anchor to root** – `/build/` ignores only directories named `build` at the root of each indexed project.
- **Segment wildcards** – `**/tmp/` skips `tmp` directories at any depth.
- **Negation** – `!important/` re-includes a directory previously ignored by a broader pattern.
- **Conditional hints** – Combining ignore rules with hint tokens (e.g., `fly service api`) lets you keep broad ignores while querying niche paths.

Test ignore patterns by running `flyctl --reindex` with `FLY_LOG_LEVEL=TRACE` (future roadmap) or by temporarily printing directories during indexing.

---

## Frequently Asked Questions

- **Can I share indices between machines?**  
  Not recommended. The database stores absolute paths; copy the relevant `.flyRoots` file instead and reindex on each machine.

- **How do I exclude specific repositories entirely?**  
  Remove the path from `.flyRoots` and run `flyctl --reindex`. Foreign-key cascades remove all associated directories.

- **Can I index removable drives?**  
  Yes, but ensure they are mounted before running `--reindex` or performing queries; missing roots are skipped with a warning and their entries are removed.

---

Need more operational detail? Reach out via repository issues or discussions and keep the documentation updated alongside code changes.
