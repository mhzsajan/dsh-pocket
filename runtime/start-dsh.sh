#!/bin/bash
# DSH Pocket guest entrypoint (runs INSIDE the PRoot Ubuntu rootfs as /root/start-dsh.sh).
#
# Contract with the Android app:
#   * stdout: exactly one line  "DSH_READY <url>"  once dsh web prints its token URL.
#   * keeps running for as long as dsh web serves (do not exit).
#   * all dsh output is streamed to stdout as well (so the app can log it).
#   * the same URL is mirrored to /root/dsh-web.url inside the rootfs, which the app
#     can read from the host side (<files>/rootfs/root/dsh-web.url) if stdout parsing fails.
#
# Env knobs (optional):
#   DSH_START_TIMEOUT  seconds to wait for the URL line (default 600)
#   DSH_HOME           dsh home dir (default /root/.dsh)
set -u

export HOME=/root
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export TERM=xterm-256color
export LANG=C.UTF-8
export npm_config_cache=/root/.npm-cache

LOG=/root/dsh-web.log
URL_FILE=/root/dsh-web.url
STARTUP_TIMEOUT=${DSH_START_TIMEOUT:-600}

# --- DNS: make sure the guest has a usable resolver -------------------------
if [ ! -s /etc/resolv.conf ]; then
  printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf 2>/dev/null || true
fi

mkdir -p /root/.dsh 2>/dev/null || true
rm -f "$URL_FILE"
: > "$LOG"

# --- watchdog: report (stderr only) if the URL never shows up --------------
(
  sleep "$STARTUP_TIMEOUT" 2>/dev/null
  if [ ! -s "$URL_FILE" ]; then
    printf 'DSH_ERROR: no "dsh web:" url after %ss; see %s\n' "$STARTUP_TIMEOUT" "$LOG" >&2
    kill "$$" 2>/dev/null
  fi
) &
WATCHDOG_PID=$!

# --- start dsh web, stream + mirror output, announce the URL ----------------
# --port 0 lets the kernel pick a free port; --no-open avoids a browser launch.
dsh web --no-open --host 127.0.0.1 --port 0 2>&1 | {
  while IFS= read -r line; do
    printf '%s\n' "$line"
    case "$line" in
      *"dsh web: "*)
        if [ ! -s "$URL_FILE" ]; then
          url=${line#*dsh web: }
          printf '%s\n' "$url" > "$URL_FILE"
          printf 'DSH_READY %s\n' "$url"
        fi
        ;;
    esac
  done
}
DSH_RC=$?
kill "$WATCHDOG_PID" 2>/dev/null
wait "$WATCHDOG_PID" 2>/dev/null
exit "$DSH_RC"
