# Developer Build Handbook — fly v0.1

This guide targets contributors who need to build, package, and verify `fly` across Linux, macOS, and Windows. End users can follow the streamlined instructions in `ReadMe.md`; the sections below expand on tooling setup, repeatable scripts, and release hygiene.

---

## Environment Matrix

| Platform | JDK 25 | Maven 3.9+ | Notes |
|----------|--------|------------|-------|
| Linux (Ubuntu/Debian) | `sudo apt install openjdk-25-jdk` | `sudo apt install maven` | Ensure `$JAVA_HOME` and `$PATH` update after installation. |
| Linux (Fedora/RHEL) | `sudo dnf install java-25-openjdk-devel` | `sudo dnf install maven` | SELinux users may need to allow writes under `~/.local/share/fly`. |
| macOS | `brew install openjdk maven` | Bundled with Homebrew | Export `PATH="/opt/homebrew/opt/openjdk/bin:$PATH"` for Apple Silicon. |
| Windows | `winget install EclipseAdoptium.Temurin.25.JDK` | `winget install Apache.Maven` | PowerShell profile should set `JAVA_HOME` if multiple JDKs exist. |

Verify your toolchain:

```bash
java -version
mvn -version
```

---

## Common Build Tasks

| Command | Description |
|---------|-------------|
| `mvn dependency:go-offline` | Prefetch Maven dependencies (useful for CI or air-gapped environments). |
| `mvn clean package` | Produce both thin and shaded JARs (tests enabled). |
| `mvn -DskipTests package` | Package quickly without executing tests. |
| `mvn test` | Run the current test suite (placeholder for now). |
| `jar tf target/cdf-1.0-SNAPSHOT-all.jar | head` | Sanity-check shaded contents. |

Artifacts:

- Thin JAR: `target/cdf-1.0-SNAPSHOT.jar`
- Shaded JAR: `target/cdf-1.0-SNAPSHOT-all.jar` (used by distributions and docs)

---

## Platform Build & Install Recipes

### Linux

```bash
mvn clean package
sudo install -D target/cdf-1.0-SNAPSHOT-all.jar /usr/local/lib/flyctl-all.jar
```

Optional verification:

```bash
java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar --help
```

### macOS

```bash
mvn clean package
mkdir -p "$HOME/Library/Application Support/fly"
cp target/cdf-1.0-SNAPSHOT-all.jar "$HOME/Library/Application Support/fly/flyctl-all.jar"
```

If you prefer parity with Linux, copy the JAR to `/usr/local/lib` and reuse the same shell snippet.

### Windows (PowerShell)

```powershell
mvn clean package
New-Item -ItemType Directory -Force C:\tools\fly | Out-Null
Copy-Item target\cdf-1.0-SNAPSHOT-all.jar C:\tools\fly\flyctl-all.jar
```

Smoke-test:

```powershell
java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\flyctl-all.jar --help
```

---

## Shell Wrappers (Developer Reference)

### Bash / Zsh Function

```bash
fly() {
  local target
  local status

  # If the first argument starts with "--", treat it as a flag
  if [[ $1 == --* ]]; then
    java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar "$@"
    return
  }

  # Run Java and capture its stdout; interactive menus stay on stderr
  target=$(java --enable-native-access=ALL-UNNAMED -jar /usr/local/lib/flyctl-all.jar "$@")
  status=$?

  if (( status != 0 )); then
    return $status
  }

  # If the command succeeded and returned a non-empty path, cd into it
  if [[ -n $target ]]; then
    cd "$target" || return
  }
}
```

- Update the JAR path for macOS if you store it under `~/Library/Application Support/fly`.
- For Fish or other shells, wrap the call similarly—capturing stderr/stdout and checking the exit code before `cd`.

### PowerShell Function

```powershell
function fly {
  param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Args
  )
  if ($Args.Count -gt 0 -and $Args[0].StartsWith("--")) {
    & java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\flyctl-all.jar @Args
    return
  }

  # Menus and prompts stay on stderr; stdout carries the final path
  $target = & java --enable-native-access=ALL-UNNAMED -jar C:\tools\fly\flyctl-all.jar @Args
  $exitCode = $LASTEXITCODE

  if ($exitCode -ne 0) {
    return $exitCode
  }

  if (-not [string]::IsNullOrWhiteSpace($target)) {
    Set-Location $target
  }
}
```

Quote the `-jar` path if your install directory contains spaces. Reload the profile with `. $PROFILE`.

---

## Verification Checklist

1. `flyctl --add-root <path>` against a small sample directory.
2. `flyctl --reindex` succeeds and prints the indexed count.
3. `fly foo` returns a deterministic path; if multiple results appear, `fly 2` reproduces the second entry.
4. `flyctl --count` reports the correct number of directories.
5. `flyctl --reset` prints (`Reset complete; removed … indexed entries.`) and sets exit code `0`.
6. Repeat the smoke test on all supported shells (Bash/Zsh/PowerShell).

---

## Release Checklist

1. Update `pom.xml` version if shipping a tagged release.
2. Refresh documentation (`ReadMe.md`, `docs/configuration.md`, `Changelog.md`).
3. `mvn clean package` (no warnings beyond Maven/Guava deprecation).
4. Copy `target/cdf-1.0-SNAPSHOT-all.jar` to `flyctl-all.jar` (the name used by the installers).
5. Verify the shaded JAR launches with `--help` on Linux/macOS/Windows.
6. Publish artifacts or copy to distribution channel (see `docs/installer-setup.md` for details).

---

## Troubleshooting Build Failures

- **Guava `sun.misc.Unsafe` warnings**: emitted by Maven itself, safe to ignore; ensure you run with JDK 25+.
- **`Unsupported class file major version`**: indicates an older JDK on `PATH`; adjust `JAVA_HOME`.
- **Permission errors when copying JAR**: prefix with `sudo` (Linux/macOS) or run PowerShell as Administrator if copying into protected locations.
- **`No suitable driver` at runtime**: confirm you executed the shaded JAR (`*-all.jar`), not the thin artifact.
- **Windows path issues**: always quote the PowerShell path if it contains spaces, e.g., `"C:\Program Files\fly\flyctl-all.jar"`.

---

Need deeper operational details (scheduled reindex, ignore rules, config layout)? See `docs/configuration.md`. For release automation and installer upkeep, refer to `docs/installer-setup.md`.
