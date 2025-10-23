# Build & Setup Guide — cdf v0.1

This document explains how to build and bootstrap the `cdf` deterministic directory navigator from source, including shell integration, database initialization, and routine maintenance.

---

## 1. Project Snapshot

- **App name:** `cdf`
- **Version:** `v0.1` (baseline: basic reindex + jump-by-basename)
- **Language:** Java 25 (source/target)
- **Build tool:** Maven (or `mvnw` if added later)
- **Database:** SQLite stored at `~/.local/share/cdf/index.sqlite` (or `$CDF_DATA_DIR/index.sqlite`)
- **Executable entry point:** `com.inferno.Main`

---

## 2. Prerequisites

| Tool | Notes |
|------|-------|
| JDK 25 | Ensure `java` and `javac` point to the 25 toolchain (`java -version`). |
| Maven 3.9+ | Install via distro package manager or download from <https://maven.apache.org/download.cgi>. Add `mvn` to your `PATH`. |
| SQLite JDBC driver | Pulled automatically by Maven (`org.xerial:sqlite-jdbc:3.46.0.0`). |

Optional (for convenience):

- `fzf` for interactive selection (future versions).
- `cron`/`systemd` to schedule periodic reindex (future versions).

---

## 3. First-Time Build

1. Clone the repo and enter the project directory.
2. Download Maven dependencies & compile:

   ```bash
   mvn dependency:go-offline
   mvn -DskipTests package
   ```

   Maven emits both the plain artifact and a shaded executable `target/cdf-1.0-SNAPSHOT-all.jar` that bundles SQLite and other dependencies.

3. (Optional) Copy or symlink the shaded JAR somewhere on your `PATH`:

   ```bash
   sudo cp target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/cdfctl-all.jar
   ```

   Adjust destination/name as preferred; the examples below assume `/usr/local/lib/cdfctl-all.jar`.

---

## 4. Shell Integration

`cdf` is meant to be wrapped by a shell function so it can return a directory path and have your shell process the `cd` itself.

### 4.1. Bash / Zsh Function

Append the following snippet to `~/.bashrc` or `~/.zshrc` (adjust path to the shaded JAR):

```bash
cdf() {
  local target
  target=$(java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/cdfctl-all.jar "$@")
  if [ $? -eq 0 ] && [ -n "$target" ]; then
    cd "$target" || return
  fi
}
```

Reload your shell (`source ~/.bashrc`) or open a new session.

### 4.2. Autocompletion (optional)

Not yet implemented in v0.1.

---

## 5. Configure Roots & Index

1. **Add roots** (directories you want indexed):

   ```bash
   java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/cdfctl-all.jar --add-root ~/workspace
   java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/cdfctl-all.jar --add-root ~/playground
   ```

2. **Run the initial reindex**:

   ```bash
   java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/cdfctl-all.jar --reindex
   ```

   This performs a full walk of all configured roots and stores directory entries in the SQLite database (`~/.local/share/cdf/index.sqlite` by default).

3. **Jump by basename**:

   ```bash
   cdf src
   cdf services
   ```

   Version `v0.1` matches a single directory basename. Future releases will support multi-token queries.
   Registered roots persist to `~/.config/cdf/.cdfRoots`; edit this file manually if desired. Global ignore patterns live in `~/.config/cdf/.cdfIgnore`. See `docs/configuration.md` for full details and examples.

---

## 6. File Layout & Data

| Path | Description |
|------|-------------|
| `~/.local/share/cdf/index.sqlite` | SQLite database storing roots and indexed directories (override via `CDF_DATA_DIR`). |
| `~/.config/cdf/.cdfRoots` | Ordered list of registered roots (one absolute path per line). |
| `~/.config/cdf/.cdfIgnore` | Global ignore patterns (gitignore syntax, editable). |
| `src/main/java` | Application source code. |
| `pom.xml` | Maven configuration & dependencies. |
| `scripts/schema.sql` | Reference schema for manual inspection (kept in sync with embedded DDL). |

### 6.1 Example `.cdfRoots`

`cdfctl --add-root` keeps this file sorted, but you can also edit it manually:

```
# cdf roots file
# Format: <absolute-path>
/home/<user>/workspace
/home/<user>/playground
```

### 6.2 Example `.cdfIgnore`

Global ignore rules live at `~/.config/cdf/.cdfIgnore`; per-root overrides reside at `<root>/.cdfIgnore`. Both accept gitignore syntax:

```
# shared rules
.git/
node_modules/
**/build/
!**/build/.keep
```

Ignored directories are skipped entirely during reindex. See `docs/configuration.md` for a full explanation of precedence and advanced patterns.

### 6.3 Config Location Overrides

Set `CDF_CONFIG_DIR=/custom/path` to relocate `.cdfRoots` and the global `.cdfIgnore`. Use `CDF_DATA_DIR=/custom/data` to relocate the SQLite store (`index.sqlite` lives under that directory). If unset, XDG environment variables are honoured; otherwise defaults fall back to `~/.config/cdf` and `~/.local/share/cdf` respectively.

Database journaling uses WAL (`index.sqlite-wal`, `index.sqlite-shm`) when the program is running.

---

## 7. Updating / Rebuilding

- **After pulling code changes:**

  ```bash
  mvn clean package
  sudo cp target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/cdfctl-all.jar
  ```

- **If schema updates occur:** Delete or migrate the data store:

  ```bash
  rm -f ~/.local/share/cdf/index.sqlite*
  java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/cdfctl-all.jar --reindex
  ```

- **Rotate roots:** Re-run `--add-root` or `--reindex` as needed. Deleting a root removes all related directories thanks to cascading deletes.

---

## 8. Troubleshooting

- **`mvn: command not found`:** Install Maven and ensure it is on the `PATH`.
- **Java version mismatch:** Set `JAVA_HOME` to a JDK 25 installation and export `PATH=$JAVA_HOME/bin:$PATH`.
- **`No roots configured`:** Run `--add-root` before attempting `--reindex` or basename jumps.
- **Permission issues:** Ensure the shell function’s JAR path is readable and the database directory is writable.

---

## 9. Next Steps (Roadmap Reference)

- `v0.2`: multi-token search.
- `v0.3`: `.cdfIgnore` support.
- `v0.4`: MRU weighting & root prioritisation improvements.
- `v0.5`: interactive match picker, diagnostics.

Keep this document updated as new features or build changes land.

---

Happy deterministic hopping!
