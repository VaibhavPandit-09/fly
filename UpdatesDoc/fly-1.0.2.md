# fly 1.0.2 Release Notes

**Release date:** 2025-10-24  
**Highlights:** Interactive menu output routed to stderr for shell-friendly automation.

## Context

Users wrapping `fly` in shell functions capture stdout to drive an automatic `cd`. Prior releases printed multi-match menus to stdout, so the captured value included prompt text instead of the final path. The common workaround relied on the `--Multiple matches found--` sentinel, which broke once interactive input was involved.

## What Changed

- **Interactive output on stderr** – All multi-match menus, numbered options, and prompts now emit on stderr. Shell wrappers can safely capture stdout without parsing guard text.
- **Documentation refresh** – Updated README and build handbook snippets to remove the sentinel check and describe the new behaviour for Bash, Zsh, and PowerShell users.
- **New update docs** – Introduced `UpdatesDoc/WhatsNew.md` as a running summary of recent releases.

## Upgrade Notes

1. Rebuild the shaded JAR (`mvn clean package`) and replace the deployed `flyctl-all.jar`.
2. Reload your shell profile to pick up the updated function snippet (note the removal of the `--*` sentinel check).
3. No database migrations or config changes are required.

## Verification Checklist

- Trigger a multi-match query (e.g., `fly project`) and confirm the numbered menu appears while the wrapper still changes directory after you select an option.
- Run `fly --help` to verify general CLI functionality.
- Optionally inspect the stderr/stdout separation by invoking `fly` directly: `java -jar ... 2>stderr.log >stdout.log`.

## Related Issues

- Internal report: “Zsh wrapper stuck on multi-select” (no public issue ID).

Happy hopping! Let us know if additional shells or terminals need tailored guidance.
