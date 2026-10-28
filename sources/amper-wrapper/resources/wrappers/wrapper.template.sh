#!/bin/sh

#
# Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
#

# Possible environment variables:
#   KOTLIN_CLI_DOWNLOAD_ROOT        Maven repository to download the Kotlin CLI dist from.
#                                     default: https://packages.jetbrains.team/maven/p/amper/amper
#   KOTLIN_CLI_JRE_DOWNLOAD_ROOT    Url prefix to download the JRE to run the Kotlin CLI
#                                     default: https:/
#   KOTLIN_CLI_BOOTSTRAP_CACHE_DIR  Cache directory to store the extracted JRE and Kotlin CLI distribution
#   KOTLIN_CLI_JAVA_HOME            JRE to run the Kotlin CLI itself (optional, does not affect compilation)
#   KOTLIN_CLI_JAVA_OPTIONS         JVM options to pass to the JVM running the Kotlin CLI (does not affect the user's application)
#   KOTLIN_CLI_NO_WELCOME_BANNER    Disables the first-run welcome message if set to a non-empty value

set -e -u

# The version of the Kotlin Toolchain (and CLI) distribution to provision and use
kotlin_cli_version={{KOTLIN_TOOLCHAIN_VERSION}}
# Establish chain of trust from here by specifying exact checksum of Kotlin Toolchain (and CLI) distribution to be run
kotlin_cli_sha256={{KOTLIN_TOOLCHAIN_DIST_TGZ_SHA256}}

KOTLIN_CLI_DOWNLOAD_ROOT="${KOTLIN_CLI_DOWNLOAD_ROOT:-https://packages.jetbrains.team/maven/p/amper/amper}"

{{include:common.template.sh}}

# ********** Project-local version detection **********

# 1. Search upwards for an executable `amper` file and/or `project.yaml`
# Sets wrapper_script to the found wrapper path, or empty string if not found.
find_project_context() {
  wrapper_script=""
  this_script="$(realpath "$0")"
  project_dir=$(pwd)
  while [ "$project_dir" != "/" ] && [ -n "$project_dir" ]; do
    wrapper_candidate="$project_dir/kotlin"
    if [ "$this_script" = "$wrapper_candidate" ]; then
      # Found itself (local wrapper case), no need to update any version or search further.
      return 1
    fi

    if [ -f "$wrapper_candidate" ] && [ -x "$wrapper_candidate" ]; then
      # Found the wrapper — check that a project context exists alongside it
      if [ -f "$project_dir/project.yaml" ] || [ -f "$project_dir/module.yaml" ]; then
        wrapper_script="$wrapper_candidate"
        return 0
      else
        echo "WARNING: Found wrapper script '$wrapper_candidate', but no project.yaml or module.yaml near it. Skipping." >&2
        # Continue the search
      fi
    elif [ -f "$project_dir/project.yaml" ]; then
      # Found project.yaml but no executable wrapper alongside it
      echo "WARNING: Found a project.yaml in '$project_dir', but the wrapper script is missing; using Kotlin Toolchain v$kotlin_cli_version." >&2
      return 1
    fi

    project_dir=$(dirname "$project_dir")
  done
  # Do not check root '/' - it's an unlikely candidate for a project

  return 1
}

parse_project_context() {
  # Parse kotlin_cli_version and kotlin_cli_sha256 from "$wrapper_script" without executing it.
  parsed_kotlin_cli_version=$(
    sed -n 's/^kotlin_cli_version=\([A-Za-z0-9._+-]\{1,\}\)[[:space:]]*$/\1/p' "$wrapper_script" \
      | head -n 1
  )
  parsed_kotlin_cli_sha256=$(
    sed -n 's/^kotlin_cli_sha256=\([0-9a-fA-F]\{64\}\)[[:space:]]*$/\1/p' "$wrapper_script" \
      | head -n 1
  )

  if [ -z "$parsed_kotlin_cli_version" ]; then
    echo "ERROR: Suspicious local wrapper script: failed to detect the distribution version in '$wrapper_script'" >&2
    return 1
  fi
  if [ -z "$parsed_kotlin_cli_sha256" ]; then
    echo "ERROR: Suspicious local wrapper script: failed to detect the distribution checksum in '$wrapper_script'" >&2
    return 1
  fi

  # overwrite builtin values and proceed
  kotlin_cli_version=$parsed_kotlin_cli_version
  kotlin_cli_sha256=$parsed_kotlin_cli_sha256
  return 0
}

if [ -z "${KOTLIN_CLI_WRAPPER_ALWAYS_USE_INTRINSIC_VERSION:-}" ]; then
  find_project_context && parse_project_context
fi

# ********** System detection **********

# /!\ Careful: the cache dir needs to stay aligned with the other wrapper and with AmperUserCacheRoot
kernelName=$(uname -s)
case "$kernelName" in
  Darwin* )
    default_kotlin_cli_cache_dir="$HOME/Library/Caches/JetBrains/Kotlin/cli"
    ;;
  Linux* )
    default_kotlin_cli_cache_dir="$HOME/.cache/JetBrains/Kotlin/cli"
    ;;
  CYGWIN* | MSYS* | MINGW* )
    if command -v cygpath >/dev/null 2>&1; then
      default_kotlin_cli_cache_dir=$(cygpath -u "$LOCALAPPDATA\JetBrains\Kotlin\cli")
    else
      die "The 'cypath' command is not available, but the Kotlin CLI needs it. Use kotlin.bat instead, or try a Cygwin or MSYS environment."
    fi
    ;;
  *)
    die "Unsupported platform $kernelName"
    ;;
esac

kotlin_cli_cache_dir="${KOTLIN_CLI_BOOTSTRAP_CACHE_DIR:-$default_kotlin_cli_cache_dir}"

# ********** Provision the Kotlin Toolchain distribution **********

kotlin_cli_url="$KOTLIN_CLI_DOWNLOAD_ROOT/org/jetbrains/kotlin/kotlin-cli/$kotlin_cli_version/kotlin-cli-$kotlin_cli_version-dist.tgz"
kotlin_cli_target_dir="$kotlin_cli_cache_dir/kotlin-cli-$kotlin_cli_version"
download_and_extract "Kotlin Toolchain distribution v$kotlin_cli_version" "$kotlin_cli_url" "$kotlin_cli_sha256" 256 "$kotlin_cli_cache_dir" "$kotlin_cli_target_dir" "true"

# ********** Launch the Kotlin CLI **********

launcher_script="$kotlin_cli_target_dir/bin/launcher.sh"

KOTLIN_CLI_WRAPPER_PATH="$(realpath "$0")" \
exec /bin/sh "$launcher_script" "$@"
