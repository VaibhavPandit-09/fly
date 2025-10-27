#!/usr/bin/env bash

# fly installer (Linux / macOS)
# Usage (one-liner):
#   curl -fsSL https://raw.githubusercontent.com/<owner>/<repo>/master/scripts/install-fly.sh | bash
#
# Environment overrides:
#   FLY_INSTALL_REPO   -> GitHub owner/repo (default: VaibhavPandit-09/fly)
#   FLY_INSTALL_TAG    -> Release tag or "latest" (default: latest)
#   FLY_INSTALL_DIR    -> Directory to store the JAR (default: ~/.local/share/fly)
#   FLY_INSTALL_JAR    -> Name of the shaded JAR asset (default: flyctl-all.jar)
#   FLY_INSTALL_PROFILE-> Shell profile file to append function to (auto-detected otherwise)

set -euo pipefail

USER_SHELL="${SHELL##*/}"
if [[ -z "$USER_SHELL" ]]; then
  USER_SHELL="bash"
fi

REPO="${FLY_INSTALL_REPO:-VaibhavPandit-09/fly}"
TAG="${FLY_INSTALL_TAG:-latest}"
INSTALL_DIR="${FLY_INSTALL_DIR:-$HOME/.local/share/fly}"
JAR_NAME="${FLY_INSTALL_JAR:-flyctl-all.jar}"
PROFILE_PATH="${FLY_INSTALL_PROFILE:-}"

MARKER_START="# >>> fly install snippet >>>"
MARKER_END="# <<< fly install snippet <<<"

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

detect_profile() {
  if [[ -n "${PROFILE_PATH}" ]]; then
    printf '%s\n' "${PROFILE_PATH}"
    return
  fi

  case "$USER_SHELL" in
    zsh) echo "$HOME/.zshrc" ;;
    bash)
      if [[ -f "$HOME/.bashrc" ]]; then
        echo "$HOME/.bashrc"
      elif [[ -f "$HOME/.bash_profile" ]]; then
        echo "$HOME/.bash_profile"
      else
        echo "$HOME/.profile"
      fi
      ;;
    *)
      echo "$HOME/.profile"
      ;;
  esac
}

need_cmd() {
  if ! command_exists "$1"; then
    echo "Error: required command '$1' not found in PATH." >&2
    exit 1
  fi
}

check_java() {
  if ! command_exists java; then
    echo "Error: Java (JDK 25+) is required but not found. Install Java 25 and rerun the installer." >&2
    exit 1
  fi
  local version_line
  version_line="$(java -version 2>&1 | head -n1)"
  if [[ "$version_line" =~ \"([0-9]+) ]]; then
    local major="${BASH_REMATCH[1]}"
    if (( major < 25 )); then
      echo "Warning: Detected Java version $major. fly targets JDK 25+. Continue? [y/N]" >&2
      read -r reply
      if [[ ! "$reply" =~ ^[Yy]$ ]]; then
        echo "Aborting installation." >&2
        exit 1
      fi
    fi
  fi
}

download() {
  local url="$1"
  local dest="$2"

  if command_exists curl; then
    if ! curl -fL "$url" -o "$dest"; then
      echo "Failed to download fly package from $url" >&2
      echo "Ensure a release asset named 'flyctl-all.jar' exists or set FLY_INSTALL_TAG to a published tag." >&2
      exit 1
    fi
  elif command_exists wget; then
    if ! wget -qO "$dest" "$url"; then
      echo "Failed to download fly package from $url" >&2
      echo "Ensure a release asset named 'flyctl-all.jar' exists or set FLY_INSTALL_TAG to a published tag." >&2
      exit 1
    fi
  else
    echo "Error: Neither curl nor wget is available. Install one of them and retry." >&2
    exit 1
  fi
}

resolve_download_url() {
  if [[ "$TAG" == "latest" ]]; then
    printf 'https://github.com/%s/releases/latest/download/%s\n' "$REPO" "$JAR_NAME"
  else
    printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$TAG" "$JAR_NAME"
  fi
}

install_shell_snippet() {
  local profile="$1"
  local jar_path="$2"

  mkdir -p "$(dirname "$profile")"
  touch "$profile"

  if grep -Fq "$MARKER_START" "$profile"; then
    local tmp
    tmp="$(mktemp)"
    awk -v start="$MARKER_START" -v end="$MARKER_END" '
      $0 == start { skipping=1; next }
      $0 == end && skipping { skipping=0; next }
      skipping { next }
      { print }
    ' "$profile" >"$tmp"
    mv "$tmp" "$profile"
  fi

  {
    echo ""
    echo "$MARKER_START"
    echo "fly() {"
    echo "  local jar=\"$jar_path\""
    echo "  local target"
    echo "  local status"
    echo ""
    echo "  if [[ \${1:-} == \"--update\" ]]; then"
    echo "    curl -fsSL https://raw.githubusercontent.com/VaibhavPandit-09/fly/master/scripts/install-fly.sh | bash"
    echo "    return \$?"
    echo "  fi"
    echo ""
    echo "  if [[ \${1:-} == --* ]]; then"
    echo "    java --enable-native-access=ALL-UNNAMED -jar \"\$jar\" \"\$@\""
    echo "    return"
    echo "  fi"
    echo ""
    echo "  target=\$(java --enable-native-access=ALL-UNNAMED -jar \"\$jar\" \"\$@\")"
    echo "  status=\$?"
    echo ""
    echo "  if (( status != 0 )); then"
    echo "    return \$status"
    echo "  fi"
    echo ""
    echo "  if [[ -n \$target ]]; then"
    echo "    cd \"\$target\" || return"
    echo "  fi"
    echo "}"
    echo "$MARKER_END"
    echo ""
  } >>"$profile"
}

main() {
  need_cmd awk
  check_java

  local url
  url="$(resolve_download_url)"

  mkdir -p "$INSTALL_DIR"
  local jar_path="$INSTALL_DIR/$JAR_NAME"

  echo "Downloading fly package from: $url"
  download "$url" "$jar_path"
  chmod 644 "$jar_path"

  local profile
  profile="$(detect_profile)"
  if [[ "$USER_SHELL" == "fish" ]]; then
    cat <<EOF
fly installed with JAR at: $jar_path

Automatic shell integration is not yet available for fish shell.
Add the following function to ~/.config/fish/config.fish manually:

function fly
  set jar "$jar_path"

  if test (count \$argv) -gt 0
    if test \$argv[1] = "--update"
      command curl -fsSL https://raw.githubusercontent.com/VaibhavPandit-09/fly/master/scripts/install-fly.sh | bash
      return \$status
    end

    if string match -q -- "--*" \$argv[1]
      java --enable-native-access=ALL-UNNAMED -jar "$jar" \$argv
      return \$status
    end
  end

  set target (java --enable-native-access=ALL-UNNAMED -jar "$jar" \$argv)
  set status \$status

  if test \$status -ne 0
    return \$status
  end

  if test -n "\$target"
    cd "\$target"
  end
end

Reload fish after updating the function, then run:
  fly --help

To update later, run:
  fly --update
EOF
  else
    install_shell_snippet "$profile" "$jar_path"

    cat <<EOF
fly installed successfully!
- JAR: $jar_path
- Shell function appended to: $profile

Reload your shell (e.g., 'source $profile') or open a new terminal, then run:
  fly --help

To update later, run:
  fly --update
EOF
  fi

}

main "$@"
