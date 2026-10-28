{{include:die.template.sh}}
{{include:check_sha.template.sh}}

download_and_extract() {
  moniker="$1"
  file_url="$2"
  file_sha="$3"
  sha_size="$4"
  cache_dir="$5"
  extract_dir="$6"
  show_banner_on_cache_miss="$7"
  # Optional name of a function to run on the extracted files before the extraction dir is marked as complete.
  # It is called with the extraction directory as single argument.
  post_process="${8:-}"

  if [ -e "$extract_dir/.flag" ] && [ "$(cat "$extract_dir/.flag")" = "${file_sha}" ]; then
    # Everything is up-to-date in $extract_dir, do nothing
    return 0;
  fi

  mkdir -p "$cache_dir"

  # Take a lock for the download of this file
  short_sha=$(echo "$file_sha" | cut -c1-32) # cannot use the ${short_sha:0:32} syntax in regular /bin/sh
  download_lock_file="$cache_dir/download-${short_sha}.lock"
  process_lock_file="$cache_dir/download-${short_sha}.$$.lock"
  temp_file="$cache_dir/download-${short_sha}.$$.bin"
  echo $$ >"$process_lock_file"
  while ! ln "$process_lock_file" "$download_lock_file" 2>/dev/null; do
    lock_owner=$(cat "$download_lock_file" 2>/dev/null || true)
    # We use `kill -0` instead of `ps -p` as the first one is more portable
    if [ -n "$lock_owner" ] && kill -0 "$lock_owner" >/dev/null; then
      echo "Another Kotlin CLI instance (pid $lock_owner) is downloading $moniker. Awaiting the result..."
      sleep 1
    elif [ -n "$lock_owner" ] && [ "$(cat "$download_lock_file" 2>/dev/null)" = "$lock_owner" ]; then
      rm -f "$download_lock_file"
      # We don't want to simply loop again here, because multiple concurrent processes may face this at the same time,
      # which means the 'rm' command above from another script could delete our new valid lock file. Instead, we just
      # ask the user to try again. This doesn't 100% eliminate the race, but the probability of issues is drastically
      # reduced because it would involve 4 processes with perfect timing. We can revisit this later.
      die "Another Kotlin CLI instance (pid $lock_owner) locked the download of $moniker, but is no longer running. The lock file is now removed, please try again."
    fi
  done


  # An extraction left half-done must never keep the .flag file, which marks $extract_dir as complete: subsequent runs
  # would skip the extraction and use the broken directory.
  cleanup_incomplete_provisioning() {
    rm -f "$download_lock_file" "$temp_file"
    rm -rf "$extract_dir"
  }
  trap cleanup_incomplete_provisioning EXIT
  rm -f "$process_lock_file"

  unlock_and_cleanup() {
    rm -f "$download_lock_file"
    trap - EXIT
    return 0
  }

  if [ -e "$extract_dir/.flag" ] && [ "$(cat "$extract_dir/.flag")" = "${file_sha}" ]; then
    # Everything is up-to-date in $extract_dir, just release the lock
    unlock_and_cleanup
    return 0;
  fi

  if [ "$show_banner_on_cache_miss" = "true" ] && [ -z "${KOTLIN_CLI_NO_WELCOME_BANNER:-}" ]; then
      echo
      cat <<EOF
Welcome to

@@@        @@@@                               @@@    @@@
@@@      @@@@                       @@@       @@@    @@@
@@@    #@@@"                       ,@@@       @@@
@@@  ,@@@%         ,@@@@@@@,     @@@@@@@@@@   @@@    @@@    @@@  ,@@@@@,
@@@ @@@@         @@@@@%"%@@@@@   @@@@@@@@@@   @@@    @@@    @@@@@@%%@@@@@
@@@@@@%         @@@%       %@@@     @@@       @@@    @@@    @@@@      %@@%
@@@ @@@@=      #@@@         @@@#    @@@       @@@    @@@    @@@        @@@
@@@   @@@@     #@@@         @@@#    @@@       @@@    @@@    @@@        @@@
@@@    *@@@%    @@@@       @@@@     @@@       @@@    @@@    @@@        @@@
@@@      %@@@=   %@@@@*,*@@@@%      @@@@###   @@@    @@@    @@@        @@@
@@@        @@@@    "@@@@@@@"         %@@@@@   @@@    @@@    @@@        @@@
EOF
      echo
      echo "This is the first run of the Kotlin CLI v$kotlin_cli_version, so we need to download the Kotlin Toolchain."
      echo "Please give us a few seconds, subsequent runs will be faster."
      echo
  fi

  echo "Downloading $moniker..."

  rm -f "$temp_file"
  if command -v curl >/dev/null 2>&1; then
    if [ -t 1 ]; then CURL_PROGRESS="--progress-bar"; else CURL_PROGRESS="--silent --show-error"; fi
    # shellcheck disable=SC2086
    curl $CURL_PROGRESS -L --fail --retry 5 --connect-timeout 30 --output "${temp_file}" "$file_url"
  elif command -v wget >/dev/null 2>&1; then
    if [ -t 1 ]; then WGET_PROGRESS=""; else WGET_PROGRESS="-nv"; fi
    wget $WGET_PROGRESS --tries=5 --connect-timeout=30 --read-timeout=120 -O "${temp_file}" "$file_url"
  else
    die "ERROR: Please install 'wget' or 'curl', as one of them is required to download $moniker"
  fi

  check_sha "$file_url" "$temp_file" "$file_sha" "$sha_size"

  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"

  case "$file_url" in
    *".zip")
      if command -v unzip >/dev/null 2>&1; then
        unzip -q "$temp_file" -d "$extract_dir"
      else
        die "ERROR: Please install 'unzip', which is required to extract $moniker"
      fi ;;
    *)
      if command -v tar >/dev/null 2>&1; then
        tar -x -f "$temp_file" -C "$extract_dir"
      else
        die "ERROR: Please install 'tar', which is required to extract $moniker"
      fi ;;
  esac

  rm -f "$temp_file"

  # Note: a failing post-processing triggers the EXIT trap, which removes the incomplete extraction dir.
  # This is why we run it before writing the .flag file, which marks the extraction dir as complete.
  if [ -n "$post_process" ]; then
    "$post_process" "$extract_dir"
  fi

  echo "$file_sha" >"$extract_dir/.flag"

  # Unlock and cleanup the lock file
  unlock_and_cleanup

  echo "Download complete."
  echo
}
