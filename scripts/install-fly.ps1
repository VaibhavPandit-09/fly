<#
  fly installer (Windows PowerShell)
  Usage:
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force; `
      iex "& { $(iwr https://raw.githubusercontent.com/<owner>/<repo>/master/scripts/install-fly.ps1 -UseBasicParsing) }"

  Environment overrides or parameters:
    -Repo        / $env:FLY_INSTALL_REPO   (default: VaibhavPandit-09/fly)
    -Tag         / $env:FLY_INSTALL_TAG    (default: latest)
    -InstallDir  / $env:FLY_INSTALL_DIR    (default: $env:LOCALAPPDATA\fly)
    -JarName     / $env:FLY_INSTALL_JAR    (default: flyctl-all.jar)
#>
[CmdletBinding()]
param(
  [string] $Repo,
  [string] $Tag,
  [string] $InstallDir,
  [string] $JarName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $Repo -or $Repo.Trim().Length -eq 0) {
  if ($env:FLY_INSTALL_REPO) { $Repo = $env:FLY_INSTALL_REPO } else { $Repo = "VaibhavPandit-09/fly" }
}
if (-not $Tag -or $Tag.Trim().Length -eq 0) {
  if ($env:FLY_INSTALL_TAG) { $Tag = $env:FLY_INSTALL_TAG } else { $Tag = "latest" }
}
if (-not $JarName -or $JarName.Trim().Length -eq 0) {
  if ($env:FLY_INSTALL_JAR) { $JarName = $env:FLY_INSTALL_JAR } else { $JarName = "flyctl-all.jar" }
}
if ([string]::IsNullOrWhiteSpace($InstallDir)) {
  if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $InstallDir = Join-Path $env:LOCALAPPDATA "fly"
  } else {
    $InstallDir = Join-Path $env:USERPROFILE ".fly"
  }
}

$markerStart = "# >>> fly install snippet >>>"
$markerEnd = "# <<< fly install snippet <<<"

function Test-Command {
  param([string]$Name)
  return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-Java {
  if (-not (Test-Command -Name "java")) {
    throw "Java (JDK 25+) is required but not found. Install Java 25 and rerun the installer."
  }
  $versionOutput = & java -version 2>&1 | Select-Object -First 1
  if ($versionOutput -match '"([0-9]+)') {
    $major = [int]$Matches[1]
    if ($major -lt 25) {
      Write-Warning "Detected Java version $major. fly targets JDK 25+. Press Y to continue or any other key to abort."
      $key = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
      if ($key.Character -ne 'y' -and $key.Character -ne 'Y') {
        throw "Installation aborted due to incompatible Java version."
      }
    }
  }
}

function Get-DownloadUrl {
  param([string]$Repository, [string]$ReleaseTag, [string]$AssetName)
  if ($ReleaseTag -eq "latest") {
    return "https://github.com/$Repository/releases/latest/download/$AssetName"
  }
  return "https://github.com/$Repository/releases/download/$ReleaseTag/$AssetName"
}

function Ensure-ProfileSnippet {
  param(
    [string]$ProfilePath,
    [string]$JarPath
  )

  if (-not (Test-Path $ProfilePath)) {
    $null = New-Item -ItemType File -Path $ProfilePath -Force
  }

  $content = Get-Content $ProfilePath -ErrorAction SilentlyContinue
  if ($content) {
    $skip = $false
    $filtered = foreach ($line in $content) {
      if ($line -eq $markerStart) { $skip = $true; continue }
      if ($line -eq $markerEnd) { $skip = $false; continue }
      if (-not $skip) { $line }
    }
    Set-Content -Path $ProfilePath -Value $filtered -Force
  }

$snippet = @"
$markerStart
function fly {
  param(
    [Parameter(ValueFromRemainingArguments = `$true)]
    [string[]] `$Args
  )

  if (`$Args.Count -gt 0 -and `$Args[0] -eq "--update") {
    iex "& { `$(iwr https://raw.githubusercontent.com/VaibhavPandit-09/fly/master/scripts/install-fly.ps1 -UseBasicParsing) }"
    return
  }

  if (`$Args.Count -gt 0 -and `$Args[0].StartsWith("--")) {
    & java --enable-native-access=ALL-UNNAMED -jar "$JarPath" @Args
    return
  }

  # Menus and prompts stay on stderr; stdout carries the final path
  `$target = & java --enable-native-access=ALL-UNNAMED -jar "$JarPath" @Args
  `$exitCode = `$LASTEXITCODE

  if (`$exitCode -ne 0) {
    return `$exitCode
  }

  if (-not [string]::IsNullOrWhiteSpace(`$target)) {
    Set-Location `$target
  }
}
$markerEnd
"@

  Add-Content -Path $ProfilePath -Value "`n$snippet`n"
}

function Invoke-Installer {
  Test-Java

  $downloadUrl = Get-DownloadUrl -Repository $Repo -ReleaseTag $Tag -AssetName $JarName

  if (-not (Test-Path $InstallDir)) {
    $null = New-Item -ItemType Directory -Path $InstallDir -Force
  }

  $jarPath = Join-Path $InstallDir $JarName
  Write-Host "Downloading fly package from $downloadUrl"
  try {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $jarPath -UseBasicParsing
  }
  catch {
    throw "Failed to download fly package. Ensure a release asset named 'flyctl-all.jar' exists (publish via GitHub Releases) or override -Tag to point at an existing version."
  }

  $profilePath = $PROFILE
  Ensure-ProfileSnippet -ProfilePath $profilePath -JarPath $jarPath

  Write-Host ""
  Write-Host "fly installed successfully!"
  Write-Host "  JAR: $jarPath"
  Write-Host "  Shell function appended to: $profilePath"
  Write-Host ""
  Write-Host "Reload your PowerShell session (or run '. $profilePath') and then execute:"
  Write-Host "  fly --help"
  Write-Host ""
  Write-Host "To update later, run:"
  Write-Host "  fly --update"
}

Invoke-Installer
