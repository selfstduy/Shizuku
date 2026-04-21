#!/usr/bin/env bash

set -euo pipefail

DEVICE_IP=""
PORT=5555
INTERVAL=5
ADB_BIN="${ADB_BIN:-adb}"
QUIET=0
CONNECT_ON_START=1

usage() {
  cat <<'EOF'
Usage:
  adb-auto-reconnect.sh --device-ip <ip> [--port 5555] [--interval 5] [--adb adb] [--quiet] [--no-connect-on-start]

Description:
  Keep reconnecting a target Android device over wireless ADB.
  Useful when network switches cause ADB disconnection.

Examples:
  ./scripts/adb-auto-reconnect.sh --device-ip 192.168.3.17
  ./scripts/adb-auto-reconnect.sh --device-ip 192.168.3.17 --port 5555 --interval 3
EOF
}

log() {
  if [[ "$QUIET" -eq 0 ]]; then
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
  fi
}

die() {
  echo "Error: $*" >&2
  exit 1
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --device-ip)
        [[ $# -ge 2 ]] || die "--device-ip requires a value"
        DEVICE_IP="$2"
        shift 2
        ;;
      --port)
        [[ $# -ge 2 ]] || die "--port requires a value"
        PORT="$2"
        shift 2
        ;;
      --interval)
        [[ $# -ge 2 ]] || die "--interval requires a value"
        INTERVAL="$2"
        shift 2
        ;;
      --adb)
        [[ $# -ge 2 ]] || die "--adb requires a value"
        ADB_BIN="$2"
        shift 2
        ;;
      --quiet)
        QUIET=1
        shift
        ;;
      --no-connect-on-start)
        CONNECT_ON_START=0
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "unknown argument: $1"
        ;;
    esac
  done
}

validate_args() {
  [[ -n "$DEVICE_IP" ]] || die "--device-ip is required"
  [[ "$PORT" =~ ^[0-9]+$ ]] || die "--port must be numeric"
  (( PORT >= 1 && PORT <= 65535 )) || die "--port must be between 1 and 65535"
  [[ "$INTERVAL" =~ ^[0-9]+$ ]] || die "--interval must be numeric"
  (( INTERVAL >= 1 )) || die "--interval must be >= 1"
  command -v "$ADB_BIN" >/dev/null 2>&1 || die "adb not found: $ADB_BIN"
}

adb_target() {
  echo "${DEVICE_IP}:${PORT}"
}

device_state() {
  local target="$1"
  "$ADB_BIN" devices | awk -v t="$target" '$1 == t { print $2; found=1 } END { if (!found) print "" }'
}

connect_target() {
  local target="$1"
  local out
  out="$("$ADB_BIN" connect "$target" 2>&1 || true)"
  if [[ "$out" == *"connected to "* || "$out" == *"already connected to "* ]]; then
    log "$out"
    return 0
  fi

  if [[ -n "$out" ]]; then
    log "connect failed: $out"
  else
    log "connect failed: unknown error"
  fi
  return 1
}

main_loop() {
  local target
  target="$(adb_target)"
  local previous=""

  if [[ "$CONNECT_ON_START" -eq 1 ]]; then
    connect_target "$target" || true
  fi

  log "watching $target (every ${INTERVAL}s). Press Ctrl+C to stop."

  while true; do
    local state
    state="$(device_state "$target")"

    if [[ "$state" == "device" ]]; then
      if [[ "$previous" != "device" ]]; then
        log "$target is connected."
      fi
      previous="device"
      sleep "$INTERVAL"
      continue
    fi

    if [[ "$state" != "$previous" ]]; then
      if [[ -n "$state" ]]; then
        log "$target state is '$state', trying reconnect..."
      else
        log "$target is disconnected, trying reconnect..."
      fi
    fi
    previous="$state"

    connect_target "$target" || true
    sleep "$INTERVAL"
  done
}

trap 'log "stopped."; exit 0' INT TERM

parse_args "$@"
validate_args
main_loop

