<#
  fly installer (Windows PowerShell)
  Usage:
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force; `
      iex "& { $(iwr https://raw.githubusercontent.com/<owner>/<repo>/main/scripts/install-fly.ps1 -UseBasicParsing) }"

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
    [Parameter(ValueFromRemainingArguments = \$true)]
    [string[]] \$Args
  )

  if (\$Args.Count -gt 0 -and \$Args[0].StartsWith("--")) {
    & java --enable-native-access=ALL-UNNAMED -jar "$JarPath" @Args
    return
  }

  \$target = & java --enable-native-access=ALL-UNNAMED -jar "$JarPath" @Args
  \$exitCode = \$LASTEXITCODE

  if (\$target -and \$target.TrimStart().StartsWith("--")) {
    Write-Output \$target
    return
  }

  if (\$exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace(\$target)) {
    Set-Location \$target
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
  Invoke-WebRequest -Uri $downloadUrl -OutFile $jarPath -UseBasicParsing

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
  Write-Host "To update later, rerun this installer."
}

Invoke-Installer
