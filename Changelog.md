# Changelog

## 2025-10-22

- **Issue:** Windows installs defaulted to Unix-specific config and data paths, creating state under `%HOMEPATH%\.config` and failing to reuse legacy `cdf` data.  
  **Resolution:** Config and repository layers now detect Windows automatically, preferring `%APPDATA%\fly` and `%LOCALAPPDATA%\fly`, while still honouring legacy overrides (`CDF_*`) and documented environment variables.

- **Issue:** `flyctl --reset` returned the number of deleted rows as the process exit code, causing successful resets to look like failures in calling scripts.  
  **Resolution:** CLI now reports a success message and exits with code `0` after reset, preserving the deleted row count in the message.

- **Issue:** Multi-token hint filtering relied on the default JVM locale, producing incorrect results in certain locales (e.g., Turkish).  
  **Resolution:** Hint comparisons are now performed using `Locale.ROOT` for deterministic, case-insensitive matching.

## 2025-10-21

- **Issue:** SLF4J and native-access warnings still appeared during `cdf` execution even after bundling dependencies.  
  **Cause:** Runtime lacked an SLF4J binder and the SQLite driver attempted to load native libraries before the disable flag was applied.  
  **Resolution:** Added an in-jar `StaticLoggerBinder`, set the SQLite disable flag during class loading, and documented the need to launch with `--enable-native-access=ALL-UNNAMED` to keep the JDK from warning about native loading.

- **Issue:** Running `cdf` from different working directories produced separate SQLite databases (e.g., commands only worked after `cd ~`).
  **Cause:** Default DB path was relative to the process working directory (`data/index.sqlite`).
  **Resolution:** Repository now resolves the data store via XDG paths (`~/.local/share/cdf/index.sqlite` by default) with an optional `CDF_DATA_DIR` override.

- **Issue:** CLI emitted noisy SLF4J warnings and native-access notices when running commands (e.g., `cdf itdoes`).  
  **Cause:** SQLite JDBC driver tried to load native libraries and SLF4J had no binding on the runtime classpath.  
  **Resolution:** Added `slf4j-nop` dependency to suppress logging and disabled SQLite native loading via a system property before driver initialisation.

- **Issue:** Build failed (`variable depth might already have been assigned`) during `mvn clean package`.  
  **Cause:** `DirectoryIndexer` declared the `depth` local variable as `final` while assigning it within a try/catch block.  
  **Resolution:** Removed the redundant `final` so compilation succeeds.

- **Issue:** Packaged JAR lacked the SQLite driver, causing “No suitable driver found” errors.  
  **Cause:** Original build only produced the thin artifact without dependencies.  
  **Resolution:** Configured Maven Shade plugin to create `cdf-1.0-SNAPSHOT-all.jar` with dependencies and updated documentation to use the shaded JAR.
