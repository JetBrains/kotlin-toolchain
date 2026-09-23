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

powershell.exe -NoProfile -File "%~dp0/build-sources/build-in-alt-screen.ps1"
if errorlevel 1 goto fail

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

set KOTLIN_FROM_SOURCES=true
set AMPER_BUILD_DIR=build-from-sources
set KOTLIN_CLI_WRAPPER_PATH=%~f0
rem We use busybox here because it doesn't reinterpret the user-passed command-line arguments (that we pass via %*).
rem Also this way we can use the unified launcher script (.sh)
rem The '& call' after the launcher run is to avoid the "Terminate batch job (Y/N)?" prompt
"%busybox_exe%" sh "%unpacked_cli_bin_dir%\launcher.sh" %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem We use "%COMSPEC%" /d /c exit so that and/or operators work properly when calling kotlin.bat directly from a script
@rem without the `call` command. With a plain `exit /B %ERRORLEVEL%`, using `kotlin.bat && echo success` would print
@rem 'success' even in case of failure. Consumers would have to use `call kotlin.bat && echo success` for it to work.`
"%COMSPEC%" /d /c exit %ERRORLEVEL%
