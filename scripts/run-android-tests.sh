#!/usr/bin/env bash
# Show actual test progress and capture a stalled emulator before terminating Gradle.
set -euo pipefail

reports=app/build/reports/instrumentation
mkdir -p "$reports"
: > "$reports/stalled"
adb logcat -c
adb logcat -v threadtime TestRunner:I AndroidRuntime:E '*:S' | tee "$reports/test-progress.log" &
log_pid=$!
test_pid=
watchdog_pid=
cleanup() {
  for pid in "$watchdog_pid" "$log_pid"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  done
  timeout 10s adb logcat -d > "$reports/logcat.txt" 2>&1 || true
}
trap cleanup EXIT

capture() {
  timeout 10s adb shell dumpsys activity top > "$reports/activity.txt" 2>&1 || true
  timeout 10s adb shell dumpsys window > "$reports/window.txt" 2>&1 || true
  timeout 10s adb shell ps -A > "$reports/processes.txt" 2>&1 || true
  timeout 10s adb exec-out screencap -p > "$reports/screenshot.png" 2>/dev/null || true
  local app_pid
  app_pid=$(timeout 10s adb shell pidof org.cqutosp.hydros | tr -d '\r') || true
  if [[ -n "$app_pid" ]]; then
    timeout 10s adb shell run-as org.cqutosp.hydros kill -3 "$app_pid" 2>/dev/null || true
  fi
}

timeout --signal=TERM --kill-after=30s 15m ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=org.cqutosp.hydros.CellularRecoveryTest &
test_pid=$!
(
  while kill -0 "$test_pid" 2>/dev/null; do
    sleep 15
    last_progress=$(stat -c %Y "$reports/test-progress.log")
    if (( $(date +%s) - last_progress >= 180 )); then
      echo '::error::No TestRunner progress for 180 seconds; capturing emulator diagnostics.'
      capture
      echo stalled > "$reports/stalled"
      kill -TERM "$test_pid" 2>/dev/null || true
      break
    fi
  done
) &
watchdog_pid=$!

set +e
wait "$test_pid"
result=$?
set -e
if [[ -s "$reports/stalled" ]]; then result=124; fi
if (( result != 0 )); then capture; fi
exit "$result"
