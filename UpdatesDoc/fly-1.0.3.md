# fly 1.0.3 Release Notes

**Release date:** 2025-10-25  
**Highlights:** Wrapper-level `fly --update` command for one-line upgrades across shells.

## Context

Users regularly reinstall `fly` by re-running the installer scripts to fetch the latest shaded JAR. The command to do so was easy to forget and differed across shells, leading to inconsistent upgrades.

## What Changed

- **Wrapper updates** – Bash/Zsh, Fish, and PowerShell wrappers now check for `--update` and pipe the appropriate installer script (Bash or PowerShell) to refresh the local install.
- **Installer snippets** – `scripts/install-fly.sh` and `scripts/install-fly.ps1` append the new wrapper logic automatically (including update messaging).
- **Docs & checklists** – README, Build handbook, and changelog now document the updater and add it to verification/release steps.

## Upgrade Notes

1. Run `fly --update` to pull the latest shaded JAR and rewrite your shell snippet.
2. If you maintain custom wrappers, copy the updated logic (including the `jar` variable and stderr handling) from the README.
3. Manual upgrades remain available if you prefer to copy the JAR yourself.

## Verification Checklist

- Execute `fly --update` and confirm the installer completes without errors.
- Open a fresh shell and run `fly --help`.
- Trigger a multi-match query to ensure stderr/stdout separation still works with the refreshed wrapper.

## Related Issues

- Internal request: “Expose update command via wrapper”.

Enjoy quicker upgrades!
