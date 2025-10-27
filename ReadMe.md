# fly — Deterministic Directory Navigator

`fly` is a cross-platform command-line companion that jumps you into any indexed directory deterministically. Instead of ranking by usage heuristics, `fly` indexes the directories you care about (roots that you control) in SQLite and resolves queries by basename, optional hints, or a recently returned index. Legacy `cdf` installations remain compatible—the tool migrates or reuses their data automatically.

---

## Highlights

- **Deterministic results** – Each query resolves against a curated SQLite index, sorted by depth for predictable output.
- **Cross-platform** – Works on Linux, macOS, and Windows (PowerShell), respecting platform-specific config/data locations and legacy `cdf` paths.
- **Zero-noise runtime** – Bundled SLF4J NOP binder and disabled native loading keep the CLI quiet.
- **Token hints** – Provide extra path hints (e.g., `fly api service`) to filter results.
- **Last-call recall** – Re-run `fly 2` to reuse the second entry returned by the previous multi-match.
- **Shaded distribution** – Packaging produces a self-contained JAR (`cdf-1.0-SNAPSHOT-all.jar`) with SQLite and logging dependencies baked in.

---

## How `fly` Works

1. **Roots** – You register one or more root directories. They are stored in a config file (`.flyRoots`) and mirrored into SQLite.
2. **Indexing** – `fly --reindex` traverses each root, recording directory metadata (basename, full path, depth, modified time, slash-delimited segments). Ignore rules (global and per-root) prune unwanted directories.
3. **Queries** – `fly <basename>` fetches matching directories, sorts them, and prints the result. Subsequent hint tokens narrow matches.
4. **Jumping** – Wrap the CLI with a shell function that executes the JAR and `cd`s into the returned path when successful.

Code structure:

| Module | Responsibility |
|--------|----------------|
| `com.inferno.cli.FlyCli` | Parses CLI arguments and delegates to logic/data layers. |
| `com.inferno.logic.DirectoryIndexer` | Walks roots, respects ignore files, writes directory metadata. |
| `com.inferno.logic.JumpPaths` | Resolves basename/hint queries and persists “last call” results. |
| `com.inferno.config.ConfigManager` | Manages config layout, migrations, ignore patterns. |
| `com.inferno.database.CdfRepository` | JDBC wrapper for SQLite with schema bootstrapping and queries. |

---

## Quick Start (TL;DR)

1. Install JDK 25 and Maven 3.9+ (see platform notes below).
2. Clone this repository and run:
   ```bash
   mvn clean package
   ```
3. Copy `target/cdf-1.0-SNAPSHOT-all.jar` somewhere convenient (`/usr/local/lib/fly-all.jar`, `~/Library/Application Support/fly/fly-all.jar`, or `C:\tools\fly\fly-all.jar`).
4. Add the matching shell function (Bash/Zsh for Linux/macOS, PowerShell for Windows).
5. Register a root, reindex, and start jumping:
   ```bash
   fly --add-root ~/workspace
   fly --reindex
   fly services
   ```

---

## One-Step Installation

Prefer an automated setup? As long as Java 25 is on your PATH, you can bootstrap `fly` with a single command.

### Linux / macOS

```bash
curl -fsSL https://raw.githubusercontent.com/VaibhavPandit-09/fly/master/scripts/install-fly.sh | bash
```

The script downloads the shaded JAR into `~/.local/share/fly`, appends the shell wrapper to your default profile (`~/.bashrc` or `~/.zshrc`), and keeps reruns idempotent. Override defaults by exporting any of:

- `FLY_INSTALL_REPO` (`owner/repo`, default `VaibhavPandit-09/fly`)
- `FLY_INSTALL_TAG` (GitHub release tag, default `latest`)
- `FLY_INSTALL_DIR` (where to store the JAR, default `~/.local/share/fly`)
- `FLY_INSTALL_JAR` (asset name, default `fly-all.jar`)
- `FLY_INSTALL_PROFILE` (explicit profile file)

After the installer runs, reload your shell (`source ~/.bashrc`) and try `fly --help`. If you see a 404 during the download step, publish a release asset named `fly-all.jar` (see `docs/installer-setup.md`).

### Windows (PowerShell 5+)

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force;
iex "& { $(iwr https://raw.githubusercontent.com/VaibhavPandit-09/fly/master/scripts/install-fly.ps1 -UseBasicParsing) }"
```

The PowerShell installer mirrors the Bash behaviour: it places the JAR under `%LOCALAPPDATA%\fly`, updates your `$PROFILE` with the wrapper function, and can be rerun to upgrade. You can customize the same settings via environment variables (`FLY_INSTALL_*`) or parameters (`-Repo`, `-Tag`, `-InstallDir`, `-JarName`). A 404 during download indicates the release asset `fly-all.jar` is missing.

Need to audit the scripts first? They live in [`scripts/install-fly.sh`](scripts/install-fly.sh) and [`scripts/install-fly.ps1`](scripts/install-fly.ps1).

---

## Platform Setup Guides

### Linux

1. **Prerequisites**
   ```bash
   sudo apt install openjdk-25-jdk maven          # Ubuntu / Debian
   # or
   sudo dnf install java-25-openjdk-devel maven   # Fedora / RHEL
   ```
   Confirm with `java -version` and `mvn -version`.

2. **Build & package**
   ```bash
   mvn clean package
   ```

3. **Install the shaded JAR**
   ```bash
   sudo install -D target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/fly-all.jar
   ```

4. **Shell integration** – See [Shell Integration (Bash/Zsh)](#shell-integration-bashzsh).

### macOS

1. **Prerequisites**
   ```bash
    brew install openjdk maven
   ```
   Ensure the Homebrew JDK is on your PATH (Intel uses `/usr/local`, Apple Silicon uses `/opt/homebrew`):
   ```bash
   export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
   ```

2. **Build & package**
   ```bash
   mvn clean package
   ```

3. **Install the shaded JAR**
   ```bash
   mkdir -p "$HOME/Library/Application Support/fly"
   cp target/cdf-1.0-SNAPSHOT-all.jar "$HOME/Library/Application Support/fly/fly-all.jar"
   ```
   (You may keep using `/usr/local/lib` if you prefer parity with Linux.)

4. **Shell integration** – macOS defaults to Zsh. Add the Bash/Zsh function to `~/.zshrc` and update the JAR path.

5. **Optional** – To align with macOS conventions, set custom config/data roots:
   ```bash
   export FLY_CONFIG_DIR="$HOME/Library/Application Support/fly/config"
   export FLY_DATA_DIR="$HOME/Library/Application Support/fly/data"
   ```

### Windows

1. **Prerequisites**
   ```powershell
   winget install EclipseAdoptium.Temurin.25.JDK
   winget install Apache.Maven
   ```
   (Chocolatey alternative: `choco install temurin17 maven`.)

2. **Build & package**
   ```powershell
   mvn clean package
   ```

3. **Install the shaded JAR**
   ```powershell
   New-Item -ItemType Directory -Force C:\tools\fly | Out-Null
   Copy-Item target\cdf-1.0-SNAPSHOT-all.jar C:\tools\fly\fly-all.jar
   ```

4. **Shell integration** – Add the PowerShell function to your profile (see below).

---

## Shell Integration (Bash/Zsh)

Add this function to `~/.bashrc`, `~/.bash_profile`, or `~/.zshrc`, adjusting the JAR path for your installation:

```bash
fly() {
  local target

  # Pass-through for CLI flags (no directory change)
  if [[ $1 == --* ]]; then
    java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/fly-all.jar "$@"
    return
  }

  # Run Java and capture the output
  target=$(java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/fly-all.jar "$@")

  # Multi-match output starts with "--"
  if [[ $target == --* ]]; then
    echo "$target"
    return
  }

  # Change directory only when the command succeeded and returned a non-empty path
  if [[ $? -eq 0 && -n $target ]]; then
    cd "$target" || return
  }
}
```

For macOS, replace `/usr/local/lib/fly-all.jar` with `"${HOME}/Library/Application Support/fly/fly-all.jar"` (remember to quote the path).

Reload your shell (`source ~/.bashrc`, `source ~/.zshrc`, or start a new terminal).

---

## Shell Integration (PowerShell)

Edit your PowerShell profile (`code $PROFILE`) and add:

```powershell
function fly {
  param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Args
  )

  if ($Args.Count -gt 0 -and $Args[0].StartsWith("--")) {
    & java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\fly-all.jar @Args
    return
  }

  $target = & java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\fly-all.jar @Args
  $exitCode = $LASTEXITCODE

  if ($target -and $target.TrimStart().StartsWith("--")) {
    Write-Output $target
    return
  }

  if ($exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($target)) {
    Set-Location $target
  }
}
```

Reload the profile with `. $PROFILE` or open a new Windows Terminal session.

---

## CLI Usage

| Command | Description |
|---------|-------------|
| `fly --add-root <path>` | Register or update a root directory. |
| `fly --list-roots` | Print configured roots and the active config directory. |
| `fly --count` | Show the total number of indexed directories. |
| `fly --reindex` | Rebuild the directory index for all roots. |
| `fly --reset` | Drop all roots and indexed directories (prints number removed). |
| `fly <basename>` | Resolve a basename and print the best match. |
| `fly hint1 hint2 basename` | Filter results using optional hint tokens before the basename. |
| `fly <index>` | Reuse a numbered entry from the previous multi-match. |

Example session:

```bash
fly --add-root ~/workspace
fly --add-root ~/sandbox
fly --reindex
fly services
fly 2
fly --count
```

---

## Configuration & Data Locations

| Purpose | Linux | macOS | Windows | Notes |
|---------|-------|-------|---------|-------|
| Roots list | `~/.config/fly/.flyRoots` | `~/.config/fly/.flyRoots` (override via `FLY_CONFIG_DIR`) | `%APPDATA%\fly\.flyRoots` | Legacy `.cdfRoots` files are migrated or read automatically. |
| Global ignore | `~/.config/fly/.flyIgnore` | `~/.config/fly/.flyIgnore` | `%APPDATA%\fly\.flyIgnore` | Gitignore syntax; `.cdfIgnore` files are still honoured. |
| Per-root ignore | `<root>/.flyIgnore` | `<root>/.flyIgnore` | `<root>/.flyIgnore` | `.cdfIgnore` also respected for backwards compatibility. |
| SQLite database | `~/.local/share/fly/index.sqlite` | `~/.local/share/fly/index.sqlite` (set `FLY_DATA_DIR` to use `~/Library/Application Support/fly`) | `%LOCALAPPDATA%\fly\index.sqlite` | SQLite WAL (`index.sqlite-wal`, `index.sqlite-shm`) is enabled. |

Environment overrides:

| Variable | Effect |
|----------|--------|
| `FLY_CONFIG_DIR` | Relocate `.flyRoots` and the global `.flyIgnore`. |
| `FLY_DATA_DIR` | Relocate the SQLite data directory. |
| `CDF_CONFIG_DIR`, `CDF_DATA_DIR` | Legacy overrides still honoured when modern vars are absent. |
| `XDG_CONFIG_HOME`, `XDG_DATA_HOME` | Applied automatically on Unix-like systems. |

---

## macOS Notes

- macOS defaults to Zsh; place the shell function in `~/.zshrc` (or `~/.zprofile`) and restart the terminal.
- Spotlight or Time Machine can change directory metadata; re-run `fly --reindex` after large moves.
- To embrace macOS conventions, set `FLY_CONFIG_DIR`/`FLY_DATA_DIR` to subdirectories of `~/Library/Application Support/fly`.
- Automate reindexing with `launchd` by pointing a job at `/usr/bin/java --enable-native-access=ALL-UNNAMED -jar <path>/fly-all.jar --reindex`.

---

## Maintenance & Upgrades

- **Upgrade**:
  ```bash
  git pull
  mvn clean package
  sudo cp target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/fly-all.jar          # Linux/macOS
  Copy-Item target\cdf-1.0-SNAPSHOT-all.jar C:\tools\fly\fly-all.jar          # Windows
  ```
- **Reindex**: `fly --reindex`
- **Reset state**: `fly --reset`
- **Inspect database**: `sqlite3 ~/.local/share/fly/index.sqlite ".tables"` (adjust path per OS)
- **One-step installer upkeep**: follow `docs/installer-setup.md` when publishing new releases or relocating assets.

---

## Development

- Run tests (currently placeholder):
  ```bash
  mvn test
  ```
- Package without tests:
  ```bash
  mvn -DskipTests package
  ```
- Inspect shaded JAR:
  ```bash
  jar tf target/cdf-1.0-SNAPSHOT-all.jar | head
  ```

Project layout:

| Path | Description |
|------|-------------|
| `src/main/java/com/inferno/cli` | CLI entry points and argument parsing. |
| `src/main/java/com/inferno/logic` | Indexing and jump resolution logic. |
| `src/main/java/com/inferno/config` | Configuration management and ignore rules. |
| `src/main/java/com/inferno/database` | SQLite repository layer. |
| `scripts/` | Helper scripts (schema references, utilities). |
| `docs/` | Supplemental documentation. |

---

## Troubleshooting

- **`mvn` not found** – Install Maven and ensure it is exported on `PATH`.
- **Java version mismatch** – Set `JAVA_HOME` to a JDK 25 installation (`export JAVA_HOME=/path/to/jdk`).
- **`fly --reindex` fails** – Ensure roots exist and are readable; check ignore rules.
- **Database locked** – Close other clients and remove WAL files (`index.sqlite-wal`, `index.sqlite-shm`) when the CLI is not running.
- **Shell function prints nothing** – Confirm the JAR path and verify `java --enable-native-access=ALL-UNNAMED -jar ...` runs successfully on its own.
- **PowerShell profile not loading** – `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

---

## Roadmap & Changelog

- Feature roadmap lives in repository issues/projects.
- Detailed history: [`Changelog.md`](Changelog.md).

---

## Contributing

1. Fork and branch (`git checkout -b feature/my-change`).
2. Keep documentation in sync with behaviour (README, Build.md, docs/configuration.md, Changelog.md).
3. Run `mvn clean package` before submitting pull requests.
4. Describe testing steps, platforms covered, and any doc updates in the PR.

---

## License

No explicit license is currently provided. Contact the maintainers before redistributing binaries or derived work.

---

Happy deterministic hopping!
