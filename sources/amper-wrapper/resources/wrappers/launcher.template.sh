#!/bin/sh

#
# Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
#

set -e -u

KOTLIN_CLI_JRE_DOWNLOAD_ROOT="${KOTLIN_CLI_JRE_DOWNLOAD_ROOT:-https:/}"

{{include:common.template.sh}}

# ********** System detection **********

kernelName=$(uname -s)
arch=$(uname -m)
case "$kernelName" in
  Darwin* )
    simpleOs="macos"
    jre_os="macosx"
    jre_archive_type="tar.gz"
    default_kotlin_cli_cache_dir="$HOME/Library/Caches/JetBrains/Kotlin/cli"
    ;;
  Linux* )
    simpleOs="linux"
    jre_os="linux"
    jre_archive_type="tar.gz"
    default_kotlin_cli_cache_dir="$HOME/.cache/JetBrains/Kotlin/cli"
    # If linux runs in 32-bit mode, we want the "fake" 32-bit architecture, not the real hardware,
    # because in this mode linux cannot run 64-bit binaries.
    # shellcheck disable=SC2046
    arch=$(linux$(getconf LONG_BIT) uname -m)
    ;;
  CYGWIN* | MSYS* | MINGW* )
    simpleOs="windows"
    jre_os="win"
    jre_archive_type=zip
    if command -v cygpath >/dev/null 2>&1; then
      default_kotlin_cli_cache_dir=$(cygpath -u "$LOCALAPPDATA\JetBrains\Kotlin\cli")
    else
      die "The 'cygpath' command is not available, but the Kotlin CLI needs it. Use kotlin.bat instead, or try a Cygwin or MSYS environment."
    fi
    ;;
  Windows_NT* ) # this is busybox-w32
    simpleOs="windows"
    jre_os="win"
    jre_archive_type=zip
    # no need to `cygpath`:
    # a) there is no such thing on busybox-w32
    # b) it's not needed: the env vars already contain converted paths
    default_kotlin_cli_cache_dir="$LOCALAPPDATA/JetBrains/Kotlin/cli"
    ;;
  *)
    die "Unsupported platform $kernelName"
    ;;
esac

kotlin_cli_cache_dir="${KOTLIN_CLI_BOOTSTRAP_CACHE_DIR:-$default_kotlin_cli_cache_dir}"

# ********** Provision JRE for the Kotlin CLI **********

if [ "$simpleOs" = "windows" ]; then
  java_binary="java.exe"
else
  java_binary="java"
fi

# Prints the path to the actual JRE home inside the given extracted JRE directory. The home is not always the
# directory itself: the archive may contain a single nested directory, and macOS JREs nest it in Contents/Home.
find_real_jre_home() {
  extracted_jre_dir="$1"
  real_jre_home=
  for d in "$extracted_jre_dir" "$extracted_jre_dir"/* "$extracted_jre_dir"/Contents/Home "$extracted_jre_dir"/*/Contents/Home; do
    if [ -e "$d/bin/$java_binary" ]; then
      real_jre_home="$d"
    fi
  done

  if [ -z "$real_jre_home" ]; then
    die "Unable to find bin/$java_binary under $extracted_jre_dir"
  fi
  echo "$real_jre_home"
}

if [ "x${KOTLIN_CLI_JAVA_HOME:-}" = "x" ]; then
  case $arch in
    x86_64 | x64)    jre_arch="x64" ;;
    aarch64 | arm64) jre_arch="aarch64" ;;
    *) die "Unsupported architecture $arch" ;;
  esac

  # Auto-updated from syncVersions.main.kts, do not modify directly here
  zulu_version=25.38.201
  java_version=25.0.5.1

  platform="$jre_os $jre_arch"
  case $platform in
    "macosx x64")     jre_sha256=f108d12af15002038c45b892a3615580b946deba911f57346fa66e15160e7f18 ;;
    "macosx aarch64") jre_sha256=fd5472b22068508afa656ac46da8ea984e180500882dfab8f9fe6253a2acd70e ;;
    "linux x64")      jre_sha256=12277af4957cd249dc7aa50017ddde967a826fa48971b2411752fefe48e5ca8b ;;
    "linux aarch64")  jre_sha256=164c7293bc17dcd21e56f84778cacfe57501a66c7ad3265b7f9f36c4cd9ec1e0 ;;
    "win x64")        jre_sha256=6c2c84c1541ea2db58bf15387454b21911505ede8d02cfcb7afba2de680ae379 ;;
    "win aarch64")    jre_sha256=deab7c8838b0d50b82b5cafebcc53e41f55c2efea200c26f508a6c21a3c422d1 ;;
    *) die "Unsupported platform $platform" ;;
  esac

  # URL for the JRE (see https://api.azul.com/metadata/v1/zulu/packages?release_status=ga&include_fields=java_package_features,os,arch,hw_bitness,abi,java_package_type,sha256_hash,size,archive_type,lib_c_type&java_version=25&os=macos,linux,win)
  # https://cdn.azul.com/zulu/bin/zulu25.28.85-ca-jre25.0.0-macosx_aarch64.tar.gz
  # https://cdn.azul.com/zulu/bin/zulu25.28.85-ca-jre25.0.0-linux_x64.tar.gz
  jre_url="$KOTLIN_CLI_JRE_DOWNLOAD_ROOT/cdn.azul.com/zulu/bin/zulu$zulu_version-ca-jre$java_version-${jre_os}_$jre_arch.$jre_archive_type"
  jre_target_dir="$kotlin_cli_cache_dir/zulu$zulu_version-ca-jre$java_version-${jre_os}_$jre_arch"

  download_and_extract "Kotlin CLI runtime v$zulu_version" "$jre_url" "$jre_sha256" 256 "$kotlin_cli_cache_dir" "$jre_target_dir" "false"

  effective_kotlin_cli_java_home=$(find_real_jre_home "$jre_target_dir")
else
  effective_kotlin_cli_java_home="$KOTLIN_CLI_JAVA_HOME"
fi

java_exe="$effective_kotlin_cli_java_home/bin/$java_binary"
if [ '!' -x "$java_exe" ]; then
  die "Unable to find bin/java executable at $java_exe"
fi

# ********** Distribution path detection **********

# We might need to resolve symbolic links here
script_path=$(realpath "$0")
# $0 = "$kotlin_cli_dist_dir/bin/launcher.sh", so need two levels higher to get to the root.
kotlin_cli_dist_dir=$(dirname "$(dirname "$script_path")")

# ********** Launch the Kotlin CLI **********

# In this section we construct the command line by prepending arguments from biggest to lowest precedence:
#   1. basic main class, CLI arguments, and classpath
#   2. user JVM args (KOTLIN_CLI_JAVA_OPTIONS)
#   3. default JVM args (prepended last, which means they appear first, so they are overridden by user args)

# 1. Prepend basic launch arguments
case "$kernelName" in
  CYGWIN* | MSYS* | MINGW* )
    # Can't cygpath the '*' so it has to be outside
    classpath="$(cygpath -w "$kotlin_cli_dist_dir")\lib\*"
    ;;
  *)
    # Works for Unix paths (/foo/bar/lib/*) and Windows paths under busybox (C:/foo/bar/lib/*)
    classpath="$kotlin_cli_dist_dir/lib/*"
    ;;
esac

set -- -cp "$classpath" org.jetbrains.amper.cli.MainKt "$@"

# 2. Prepend user JVM args from AMPER_JAVA_OPTS
#
# We use "xargs" to parse quoted JVM args from inside AMPER_JAVA_OPTS.
# With -n1 it outputs one arg per line, with the quotes and backslashes removed.
#
# In Bash we could simply go:
#
#   readarray ARGS < <( xargs -n1 <<<"$var" ) &&
#   set -- "${ARGS[@]}" "$@"
#
# but POSIX shell has neither arrays nor command substitution, so instead we
# post-process each arg (as a line of input to sed) to backslash-escape any
# character that might be a shell metacharacter, then use eval to reverse
# that process (while maintaining the separation between arguments), and wrap
# the whole thing up as a single "set" statement.
#
# This will of course break if any of these variables contains a newline or
# an unmatched quote.
if [ -n "${KOTLIN_CLI_JAVA_OPTIONS:-}" ]; then
  eval "set -- $(
    printf '%s\n' "$KOTLIN_CLI_JAVA_OPTIONS" |
    xargs -n1 |
    sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
    tr '\n' ' '
  )" '"$@"'
fi

# 3. Prepend default JVM args
set -- \
    @"$kotlin_cli_dist_dir/kotlin-cli.args" \
    "$@"

# Then we can launch with the overridden $@ arguments
KOTLIN_TOOLCHAIN_DISTRIBUTION_DIR="$kotlin_cli_dist_dir" \
exec "$java_exe" "$@"
