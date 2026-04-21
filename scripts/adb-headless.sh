#!/usr/bin/env bash

set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
SERIAL=""
PROVIDER_URI="content://moe.shizuku.privileged.api.shizuku"

COMMAND=""
PAIRING_CODE=""
HOST="127.0.0.1"
PAIRING_PORT=""
CONNECT_PORT=""
DISCOVERY_TIMEOUT_MS=15000
START_TIMEOUT_MS=10000
WAIT_FOR_SERVER=1
ENABLE_TCPIP_5555=0

usage() {
  cat <<'USAGE'
Usage:
  adb-headless.sh <command> [options]

Commands:
  pair-notify-start   Start notification pairing flow on device (code is entered in notification).
  pair-notify-stop    Stop notification pairing flow.
  pair                Pair with a 6-digit pairing code from host CLI.
  start               Start Shizuku via wireless adb (requires paired key already exists).
  pair-start          Pair then start (host CLI code input + startup).

Common options:
  --serial <serial>               ADB device serial
  --provider-uri <uri>            Content provider URI
  --host <ip>                     Wireless debugging host (default: 127.0.0.1)
  --pairing-code <code>           6-digit pairing code (for pair/pair-start)
  --pairing-port <port>           Pairing port, auto-discover when omitted
  --connect-port <port>           Connect port, auto-discover when omitted
  --discovery-timeout-ms <ms>     Discovery timeout (default: 15000)
  --start-timeout-ms <ms>         Wait server timeout (default: 10000)
  --no-wait-for-server            Do not wait for Shizuku binder after startup
  --enable-tcpip-5555             Enable classic adb tcpip:5555 (will restart adbd)
  --no-enable-tcpip-5555          Disable classic adb tcpip:5555 (default)
  -h, --help                      Show this help

Examples:
  ./scripts/adb-headless.sh pair-notify-start
  ./scripts/adb-headless.sh start
  ./scripts/adb-headless.sh pair-start --pairing-code 123456
USAGE
}

die() {
  echo "Error: $*" >&2
  exit 1
}

adb_cmd() {
  if [[ -n "$SERIAL" ]]; then
    "$ADB_BIN" -s "$SERIAL" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

require_adb() {
  command -v "$ADB_BIN" >/dev/null 2>&1 || die "adb not found: $ADB_BIN"
}

bool_string() {
  if [[ "$1" -eq 1 ]]; then
    echo "true"
  else
    echo "false"
  fi
}

provider_call() {
  local method="$1"
  local arg_value="$2"

  local shell_args=(
    shell content call
    --user 0
    --uri "$PROVIDER_URI"
    --method "$method"
  )

  if [[ -n "$arg_value" ]]; then
    shell_args+=(--arg "$arg_value")
  fi

  local wait_for_server_text
  wait_for_server_text="$(bool_string "$WAIT_FOR_SERVER")"
  local enable_tcpip_text
  enable_tcpip_text="$(bool_string "$ENABLE_TCPIP_5555")"

  shell_args+=(
    --extra "host:s:${HOST}"
    --extra "discovery_timeout_ms:i:${DISCOVERY_TIMEOUT_MS}"
    --extra "start_timeout_ms:i:${START_TIMEOUT_MS}"
    --extra "wait_for_server:s:${wait_for_server_text}"
    --extra "enable_tcpip_5555:s:${enable_tcpip_text}"
  )

  if [[ -n "$PAIRING_PORT" ]]; then
    shell_args+=(--extra "pairing_port:i:${PAIRING_PORT}")
  fi

  if [[ -n "$CONNECT_PORT" ]]; then
    shell_args+=(--extra "connect_port:i:${CONNECT_PORT}")
  fi

  local out
  set +e
  out="$(adb_cmd "${shell_args[@]}" 2>&1)"
  local code=$?
  set -e

  echo "$out"
  return "$code"
}

ensure_pairing_code() {
  if [[ -n "$PAIRING_CODE" ]]; then
    return
  fi

  read -r -p "Enter pairing code: " PAIRING_CODE
  [[ -n "$PAIRING_CODE" ]] || die "pairing code is required"
}

check_bundle_success() {
  local text="$1"
  if [[ "$text" == *"ok=true"* ]]; then
    return
  fi
  die "Provider returned non-success result"
}

parse_args() {
  [[ $# -ge 1 ]] || {
    usage
    exit 1
  }
  if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    usage
    exit 0
  fi

  COMMAND="$1"
  shift

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --serial)
        [[ $# -ge 2 ]] || die "--serial requires a value"
        SERIAL="$2"
        shift 2
        ;;
      --provider-uri)
        [[ $# -ge 2 ]] || die "--provider-uri requires a value"
        PROVIDER_URI="$2"
        shift 2
        ;;
      --host)
        [[ $# -ge 2 ]] || die "--host requires a value"
        HOST="$2"
        shift 2
        ;;
      --pairing-code)
        [[ $# -ge 2 ]] || die "--pairing-code requires a value"
        PAIRING_CODE="$2"
        shift 2
        ;;
      --pairing-port)
        [[ $# -ge 2 ]] || die "--pairing-port requires a value"
        PAIRING_PORT="$2"
        shift 2
        ;;
      --connect-port)
        [[ $# -ge 2 ]] || die "--connect-port requires a value"
        CONNECT_PORT="$2"
        shift 2
        ;;
      --discovery-timeout-ms)
        [[ $# -ge 2 ]] || die "--discovery-timeout-ms requires a value"
        DISCOVERY_TIMEOUT_MS="$2"
        shift 2
        ;;
      --start-timeout-ms)
        [[ $# -ge 2 ]] || die "--start-timeout-ms requires a value"
        START_TIMEOUT_MS="$2"
        shift 2
        ;;
      --no-wait-for-server)
        WAIT_FOR_SERVER=0
        shift
        ;;
      --enable-tcpip-5555)
        ENABLE_TCPIP_5555=1
        shift
        ;;
      --no-enable-tcpip-5555)
        ENABLE_TCPIP_5555=0
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

run_command() {
  local result
  case "$COMMAND" in
    pair-notify-start)
      result="$(provider_call "adbPairingNotifyStart" "")"
      ;;
    pair-notify-stop)
      result="$(provider_call "adbPairingNotifyStop" "")"
      ;;
    pair)
      ensure_pairing_code
      result="$(provider_call "adbPair" "$PAIRING_CODE")"
      ;;
    start)
      result="$(provider_call "adbStart" "")"
      ;;
    pair-start)
      ensure_pairing_code
      result="$(provider_call "adbPairAndStart" "$PAIRING_CODE")"
      ;;
    *)
      die "unknown command: $COMMAND"
      ;;
  esac

  echo "$result"
  check_bundle_success "$result"
}

parse_args "$@"
require_adb
run_command
