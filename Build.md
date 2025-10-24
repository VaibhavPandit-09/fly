# Build & Setup Guide — fly v0.1

This document explains how to build and bootstrap the `fly` deterministic directory navigator from source, including shell integration, database initialization, and routine maintenance. Legacy `cdf` layouts are migrated or read automatically.

---

## 1. Project Snapshot

- **App name:** `fly`
- **Version:** `v0.1` (baseline: basic reindex + jump-by-basename)
- **Language:** Java 25 (source/target)
- **Build tool:** Maven (or `mvnw` if added later)
- **Database:** SQLite stored at `~/.local/share/fly/index.sqlite` (or `$FLY_DATA_DIR/index.sqlite`; legacy `cdf` locations remain supported)
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

   - Linux / macOS:

     ```bash
     sudo cp target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/flyctl-all.jar
     ```

   - Windows (PowerShell):

     ```powershell
     New-Item -ItemType Directory -Force C:\tools\fly | Out-Null
     Copy-Item target\cdf-1.0-SNAPSHOT-all.jar C:\tools\fly\flyctl-all.jar
     ```

   Adjust destination/name as preferred; the examples below assume `/usr/local/lib/flyctl-all.jar` on Unix-like systems and `C:\tools\fly\flyctl-all.jar` on Windows.

---

## 4. Shell Integration

`fly` is meant to be wrapped by a shell function so it can return a directory path and have your shell process the `cd` itself.

### 4.1. Bash / Zsh Function

Append the following snippet to `~/.bashrc` or `~/.zshrc` (adjust path to the shaded JAR):

```bash
fly() {
  local target
  target=$(java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar "$@")
  if [ $? -eq 0 ] && [ -n "$target" ]; then
    cd "$target" || return
  fi
}
```

Reload your shell (`source ~/.bashrc`) or open a new session.

### 4.2. PowerShell Function

Add this function to your PowerShell profile (`code $PROFILE`) and update the JAR path if you chose a different location:

```powershell
function fly {
  param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Args
  )
  $target = & java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\flyctl-all.jar @Args
  if ($LASTEXITCODE -eq 0 -and $target) {
    Set-Location $target
  }
}
```

Reload the session (`. $PROFILE`) or open a fresh PowerShell window.

### 4.3. Autocompletion (optional)

Not yet implemented in v0.1.

---

## 5. Configure Roots & Index

1. **Add roots** (directories you want indexed):

   ```bash
   java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --add-root ~/workspace
   java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --add-root ~/playground
   ```

2. **Run the initial reindex**:

   ```bash
   java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --reindex
   ```

   This performs a full walk of all configured roots and stores directory entries in the SQLite database (`~/.local/share/fly/index.sqlite` by default; legacy `cdf` paths are honoured).

3. **Jump by basename**:

   ```bash
   fly src
   fly services
   fly 2        # reuse the 2nd match from the previous query (if present)
   ```

   Version `v0.1` matches a single directory basename. Future releases will support multi-token queries.
   Registered roots persist to `~/.config/fly/.flyRoots`; edit this file manually if desired. Global ignore patterns live at `~/.config/fly/.flyIgnore`. Legacy `.cdf*` files are migrated or still read. See `docs/configuration.md` for full details and examples.

---

## 6. File Layout & Data

| Purpose | Linux / macOS | Windows | Notes |
|---------|----------------|---------|-------|
| SQLite database | `~/.local/share/fly/index.sqlite` | `%LOCALAPPDATA%\fly\index.sqlite` | Override via `FLY_DATA_DIR` / `CDF_DATA_DIR`; legacy `cdf` paths are reused automatically. |
| Roots list | `~/.config/fly/.flyRoots` | `%APPDATA%\fly\.flyRoots` | One absolute path per line; legacy `.cdfRoots` files are migrated or read. |
| Global ignore | `~/.config/fly/.flyIgnore` | `%APPDATA%\fly\.flyIgnore` | Gitignore syntax; legacy `.cdfIgnore` files remain supported. |
| Per-root ignore | `<root>/.flyIgnore` | `<root>/.flyIgnore` | Applies after global rules; `.cdfIgnore` respected. |
| Shell wrapper | `~/.bashrc`, `~/.zshrc` | PowerShell profile (`$PROFILE`) | Add the `fly` function to integrate `cd`. |

Repository artefacts:

| Path | Description |
|------|-------------|
| `src/main/java` | Application source code. |
| `pom.xml` | Maven configuration & dependencies. |
| `scripts/schema.sql` | Reference schema for manual inspection (kept in sync with embedded DDL). |

### 6.1 Example `.flyRoots`

`flyctl --add-root` keeps this file sorted, but you can also edit it manually:

```
# fly roots file
# Format: <absolute-path>
/home/<user>/workspace
/home/<user>/playground
```

### 6.2 Example `.flyIgnore`

Global ignore rules live at `~/.config/fly/.flyIgnore`; per-root overrides reside at `<root>/.flyIgnore`. Both accept gitignore syntax. Legacy `.cdfIgnore` files are still read automatically:

```
# shared rules
.git/
node_modules/
**/build/
!**/build/.keep
```

Ignored directories are skipped entirely during reindex. See `docs/configuration.md` for a full explanation of precedence and advanced patterns.

### 6.3 Config Location Overrides

Set `FLY_CONFIG_DIR=/custom/path` to relocate `.flyRoots` and the global `.flyIgnore`. Use `FLY_DATA_DIR=/custom/data` to relocate the SQLite store (`index.sqlite` lives under that directory). Legacy `CDF_*` overrides remain supported. If unset, XDG environment variables are honoured; otherwise defaults fall back to `~/.config/fly` and `~/.local/share/fly` on Unix-like systems, or `%APPDATA%\fly` and `%LOCALAPPDATA%\fly` on Windows (with automatic fallback to existing `cdf` paths).

Database journaling uses WAL (`index.sqlite-wal`, `index.sqlite-shm`) when the program is running.

---

## 7. Updating / Rebuilding

- **After pulling code changes:**

  ```bash
  mvn clean package
  sudo cp target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/flyctl-all.jar
  ```

  Windows (PowerShell):

  ```powershell
  mvn clean package
  Copy-Item target\cdf-1.0-SNAPSHOT-all.jar C:\tools\fly\flyctl-all.jar
  ```

- **If schema updates occur:** Delete or migrate the data store:

  ```bash
  rm -f ~/.local/share/fly/index.sqlite*
  java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --reindex
  ```

  Windows (PowerShell):

  ```powershell
  Remove-Item -Path $env:LOCALAPPDATA\fly\index.sqlite* -ErrorAction SilentlyContinue
  java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\flyctl-all.jar --reindex
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
- `v0.3`: `.flyIgnore` support (legacy `.cdfIgnore` compatibility).
- `v0.4`: MRU weighting & root prioritisation improvements.
- `v0.5`: interactive match picker, diagnostics.

Keep this document updated as new features or build changes land.

---

Happy deterministic hopping!
