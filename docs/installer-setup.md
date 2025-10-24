# Installer Setup Playbook

This guide explains how to keep the one-step installation experience working when you cut new releases or move the repository. It pairs with the user-facing installers at:

- `scripts/install-fly.sh` (Linux / macOS)
- `scripts/install-fly.ps1` (Windows PowerShell)

Both scripts assume you publish a shaded JAR named `flyctl-all.jar` on every GitHub release.

---

## 1. Build the Release Artifact

```bash
mvn clean package
cp target/cdf-1.0-SNAPSHOT-all.jar flyctl-all.jar
```

The copy step gives you a predictable asset name. Keep `flyctl-all.jar` alongside your release notes.

---

## 2. Publish the GitHub Release

1. Tag the commit (`git tag v0.x.y && git push origin v0.x.y`).
2. Draft a release on GitHub for the tag.
3. Upload `flyctl-all.jar` as a binary asset.
4. (Optional) Upload a checksum file (e.g., `flyctl-all.jar.sha256`).

> The installers fetch the latest release (`https://github.com/<owner>/<repo>/releases/latest/download/flyctl-all.jar`) by default. If you need channel-specific installers, update the scripts to point at the appropriate tag.

---

## 3. Verify the Installers

Run through the one-liners on each target platform:

- **Linux/macOS**
  ```bash
  curl -fsSL https://raw.githubusercontent.com/<owner>/<repo>/main/scripts/install-fly.sh | bash
  source ~/.bashrc    # or ~/.zshrc
  fly --help
  ```
- **Windows (PowerShell)**
  ```powershell
  Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
  iex "& { $(iwr https://raw.githubusercontent.com/<owner>/<repo>/main/scripts/install-fly.ps1 -UseBasicParsing) }"
  fly --help
  ```

If you are testing unreleased changes, export `FLY_INSTALL_TAG=<tag>` or point the scripts at a fork (`FLY_INSTALL_REPO=<owner>/<repo>`).

---

## 4. Customizing Defaults

Update both installer scripts if any of the following change:

| Change | Files to edit | Notes |
|--------|---------------|-------|
| GitHub org/repo | `scripts/install-fly.sh`, `scripts/install-fly.ps1`, README commands | Update the default `inferno/cdf` string. |
| Asset name | same | Ensure `JAR_NAME` / `$JarName` match the release asset. |
| Install directory defaults | same | Adjust `INSTALL_DIR` / `$InstallDir` logic (and README documentation). |
| Shell wrapper logic | installers + README | Keep snippets identical across README and scripts. |

After edits, update `ReadMe.md` and `Changelog.md` if behaviour changes.

---

## 5. Optional Hardening

- **Checksums:** publish `flyctl-all.jar.sha256` and have the installers verify it.
- **Channel support:** add `-Channel stable|beta` parameters that map to different release tags.
- **CI verification:** run the installers in GitHub Actions (Linux and Windows runners) to ensure the latest release always installs cleanly.

---

Keep this playbook close when shipping new versions so your single-command install keeps working. If the scripts change materially, bump the changelog and notify users in release notes.
