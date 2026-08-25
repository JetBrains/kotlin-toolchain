@echo off

@rem
@rem Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@rem

@rem Runs amper cli from sources

setlocal

goto :after_function_declarations

:fail
echo ERROR: Amper bootstrap failed, see errors above
exit /b 1

:after_function_declarations

REM ********** Build Amper from sources **********

pushd "%~dp0"
if errorlevel 1 goto fail

echo Building Kotlin Toolchain distribution from sources...
call kotlin.bat --log-level=warn do buildUnpackedDistribution
if errorlevel 1 goto fail

cls
popd
if errorlevel 1 goto fail

REM ********** Launch the Kotlin CLI from unpacked dist **********

set unpacked_cli_bin_dir=%~dp0build\tasks\_amper-cli_buildUnpacked@amper-distribution\dist\bin

rem Determine the correct busybox binary based on architecture
if "%PROCESSOR_ARCHITECTURE%"=="ARM64" (
    set busybox_exe=%unpacked_cli_bin_dir%\busybox64a.exe
) else if "%PROCESSOR_ARCHITECTURE%"=="AMD64" (
    set busybox_exe=%unpacked_cli_bin_dir%\busybox64u.exe
) else (
    echo Unsupported architecture %PROCESSOR_ARCHITECTURE% >&2
    goto fail
)

set AMPER_BUILD_DIR=build-from-sources
set KOTLIN_CLI_WRAPPER_PATH=%~f0
rem We use busybox here because it doesn't reinterpret the user-passed command-line arguments (that we pass via %*).
rem Also this way we can use the unified launcher script (.sh)
"%busybox_exe%" sh "%unpacked_cli_bin_dir%\launcher.sh" %*
exit /B %ERRORLEVEL%
