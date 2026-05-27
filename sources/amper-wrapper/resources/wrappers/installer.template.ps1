#
# Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
#

# Kotlin CLI Installer for Windows
# Possible environment variables:
#   KOTLIN_CLI_NO_MODIFY_PATH    If set to a non-empty value, skip modifying the user PATH.
#   KOTLIN_CLI_DOWNLOAD_ROOT     If set, use this URL as the base for downloading Kotlin CLI artifacts.

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$KotlinCliVersion = '{{KOTLIN_TOOLCHAIN_VERSION}}'
$KotlinCliWrapperSha256 = '{{KOTLIN_CLI_WRAPPER_SHA256}}'

if ($env:KOTLIN_CLI_DOWNLOAD_ROOT) {
    $KotlinCliDownloadRoot = $env:KOTLIN_CLI_DOWNLOAD_ROOT.TrimEnd('/')
}
else {
    $KotlinCliDownloadRoot = 'https://packages.jetbrains.team/maven/p/amper/amper'
}
$RepoBaseUrl = "$KotlinCliDownloadRoot/org/jetbrains/kotlin"
$WrapperName = 'kotlin.bat'

# ********** Utility functions **********

function Exit-WithError {
    param([string]$Message)
    Write-Host ''
    Write-Host "ERROR: $Message" -ForegroundColor Red
    Write-Host ''
    exit 1
}

function Test-IsWindows {
    # PowerShell 6+ defines $IsWindows; 5.1 is always Windows.
    $isWin = Get-Variable -Name IsWindows -ValueOnly -ErrorAction SilentlyContinue
    if ($null -ne $isWin) { return [bool]$isWin }
    return $true
}

# ********** Download wrapper **********

function Download-ToFile {
    param(
        [string]$Url,
        [string]$TargetPath
    )

    $tempFile = "$TargetPath.tmp.$PID"
    if (Test-Path $tempFile) { Remove-Item $tempFile -Force }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    try {
        & curl.exe --silent --show-error -L --fail --retry 5 --connect-timeout 30 --output $tempFile $Url 2>&1
        if ($LASTEXITCODE -ne 0) {
            Exit-WithError "Failed to download from $Url"
        }
    }
    catch {
        if (Test-Path $tempFile) { Remove-Item $tempFile -Force }
        Exit-WithError "Failed to download from ${Url}: $_"
    }

    Move-Item -Force $tempFile $TargetPath
}

# ********** Checksum verification **********

function Test-Sha256 {
    param(
        [string]$Url,
        [string]$FilePath,
        [string]$ExpectedSha
    )

    $actualSha = (Get-FileHash -Algorithm SHA256 -Path $FilePath).Hash
    if ($actualSha -ne $ExpectedSha.ToUpper()) {
        Exit-WithError "Checksum mismatch for $FilePath (downloaded from $Url): expected $ExpectedSha but got $actualSha"
    }
}

# ********** Detect existing installation **********

function Test-ExistingInInstallDir {
    param([string]$InstallDir)
    return Test-Path (Join-Path $InstallDir $WrapperName)
}

function Write-ShadowedInstallationWarnings {
    param([string]$InstallDir)

    $resolved = Resolve-Path $InstallDir -ErrorAction SilentlyContinue
    $resolvedInstallDir = if ($resolved) { $resolved.Path } else { $InstallDir }

    # Use Get-Command to find all executables matching the base name on PATH
    $commands = @(Get-Command 'kotlin' -CommandType Application -All -ErrorAction SilentlyContinue)
    foreach ($cmd in $commands) {
        $cmdPath = $cmd.Source
        if (-not $cmdPath) { continue }
        $cmdDir = Split-Path $cmdPath -Parent
        $resolved = Resolve-Path $cmdDir -ErrorAction SilentlyContinue
        $resolvedDir = if ($resolved) { $resolved.Path } else { $cmdDir }
        if ($resolvedDir -ne $resolvedInstallDir) {
            $cmdName = Split-Path $cmdPath -Leaf
            Write-Host "WARNING: Another '$cmdName' found at $cmdPath" -ForegroundColor Yellow
            Write-Host "         It may shadow or be shadowed by the script in $InstallDir" -ForegroundColor Yellow
        }
    }
}

# ********** PATH management **********

function Test-InPath {
    param([string]$Dir)
    $pathDirs = $env:PATH -split ';'
    foreach ($d in $pathDirs) {
        if ($d -eq $Dir) { return $true }
    }
    return $false
}

function Add-ToUserPath {
    param([string]$TargetDir)

    if ($env:KOTLIN_CLI_NO_MODIFY_PATH) {
        return
    }

    if (Test-InPath $TargetDir) {
        return
    }

    # Check if the directory is already referenced in the persisted user PATH
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    if ($userPath) {
        $userPathDirs = $userPath -split ';'
        foreach ($d in $userPathDirs) {
            if ($d -eq $TargetDir) {
                # Already in persisted PATH but not in current session PATH — add to current session
                $env:PATH = "$TargetDir;$env:PATH"
                return
            }
        }
    }

    # Add to the persisted user PATH
    if ($userPath) {
        $newUserPath = "$TargetDir;$userPath"
    }
    else {
        $newUserPath = $TargetDir
    }
    [Environment]::SetEnvironmentVariable('PATH', $newUserPath, 'User')

    # Also add to the current session PATH
    # But that may not work for the end-user, if they launched the installer via the full `powershell .. -c ..` command.
    $env:PATH = "$TargetDir;$env:PATH"

    Write-Host "Added $TargetDir to user PATH"
}

# ********** Main **********

function Main {
    if (-not (Test-IsWindows)) {
        Exit-WithError 'Only Windows is supported by this installer. For Linux and macOS, please use the `sh` installer instead.'
    }

    Write-Host 'Installing Kotlin CLI...'

    $wrapperUrl = "$RepoBaseUrl/kotlin-cli/$KotlinCliVersion/kotlin-cli-$KotlinCliVersion-wrapper.bat"

    # Normalize the installation directory
    $installDir = [IO.Path]::GetFullPath((Join-Path $HOME '.local\bin'))

    if (Test-ExistingInInstallDir $installDir) {
        Write-Host "Existing installation found in $installDir, it will be overwritten."
    }

    Write-ShadowedInstallationWarnings $installDir

    if (-not (Test-Path $installDir)) {
        [void](New-Item $installDir -ItemType Directory -Force)
    }

    $targetPath = Join-Path $installDir $WrapperName

    Write-Host 'Downloading Kotlin CLI wrapper script...'
    Download-ToFile -Url $wrapperUrl -TargetPath $targetPath
    Test-Sha256 -Url $wrapperUrl -FilePath $targetPath -ExpectedSha $KotlinCliWrapperSha256

    # Launch the wrapper to trigger the greeting and the distribution download
    $env:KOTLIN_CLI_WRAPPER_ALWAYS_USE_INTRINSIC_VERSION = '1'
    & $targetPath --version

    Add-ToUserPath $installDir
    Write-Host "Installed Kotlin CLI to $targetPath"
    Write-Host 'To get started you can run: kotlin --help (you may need to restart your shell first)'
}

Main
