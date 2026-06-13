#!/usr/bin/env bash
# Sandbox tests for offsite-push.sh (P2 leg). No network, no docker, no rclone:
# the three external binaries are stubbed onto PATH and their invocations recorded,
# so the tests cover the script's OWN contract - gating, the registry pre-push
# assertion, fail-isolation + alerting, and the SUCCESS line format the box greps.
#
# Run anywhere: ./infra/backup/test-offsite-push.sh   (exit 0 = all pass)

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${HERE}/offsite-push.sh"

PASS=0
FAIL=0
say() { printf '%s\n' "$*"; }
ok() { PASS=$((PASS + 1)); say "  ok - $1"; }
bad() { FAIL=$((FAIL + 1)); say "  FAIL - $1"; }

run_case() {
  # Fresh sandbox per case: stub bin dir + snapshot dir + docker "volume" + logs.
  SANDBOX="$(mktemp -d)"
  BIN="${SANDBOX}/bin"
  SNAP="${SANDBOX}/snapshots"
  VOL="${SANDBOX}/chvolume"
  CALLS="${SANDBOX}/calls.log"
  mkdir -p "$BIN" "$SNAP" "${VOL}/backup"
  : > "$CALLS"

  # rclone stub: records argv; `size --json` emits a fixed payload; copy/check obey
  # STUB_RCLONE_COPY_EXIT; rcat drains stdin (the clickhouse tar stream) so the pipe
  # closes cleanly under pipefail.
  cat > "${BIN}/rclone" <<STUB
#!/usr/bin/env bash
echo "rclone \$*" >> "${CALLS}"
case "\$1" in
  size) echo '{"count":3,"bytes":12345}' ;;
  rcat) cat >/dev/null 2>&1; exit 0 ;;
  copy) exit "\${STUB_RCLONE_COPY_EXIT:-0}" ;;
  check) exit 0 ;;
esac
exit 0
STUB
  # docker stub: `inspect` resolves the fake volume mountpoint.
  cat > "${BIN}/docker" <<STUB
#!/usr/bin/env bash
echo "docker \$*" >> "${CALLS}"
[[ "\$1" == "inspect" ]] && echo "${VOL}"
exit 0
STUB
  # curl stub: records (Discord / healthchecks) calls.
  cat > "${BIN}/curl" <<STUB
#!/usr/bin/env bash
echo "curl \$*" >> "${CALLS}"
exit 0
STUB
  chmod +x "${BIN}/rclone" "${BIN}/docker" "${BIN}/curl"
}

seed_snapshot() {
  # A complete local capture for auto_TEST: registry (>=16384B), artifacts meta,
  # and the in-volume clickhouse backup dir.
  mkdir -p "${SNAP}/auto_TEST_sqlite" "${SNAP}/auto_TEST_artifacts_meta" "${VOL}/backup/auto_TEST"
  head -c 20000 /dev/zero > "${SNAP}/auto_TEST_sqlite/registry.sqlite"
  echo "meta" > "${SNAP}/auto_TEST_artifacts_meta/m.json"
  echo "data" > "${VOL}/backup/auto_TEST/part.bin"
}

invoke() {
  PATH="${BIN}:${PATH}" SNAPSHOT_DIR="$SNAP" \
    BULLPEN_DISCORD_WEBHOOK="https://discord.example/hook" \
    "$@" bash "$SCRIPT" auto_TEST > "${SANDBOX}/out.log" 2>&1
}

say "test: unset remote -> clean no-op, nothing called"
run_case
seed_snapshot
PATH="${BIN}:${PATH}" SNAPSHOT_DIR="$SNAP" bash "$SCRIPT" > "${SANDBOX}/out.log" 2>&1
RC=$?
[[ $RC -eq 0 ]] && ok "exit 0" || bad "exit $RC (wanted 0)"
grep -q "offsite leg disabled" "${SANDBOX}/out.log" && ok "says disabled" || bad "missing disabled line"
[[ ! -s "$CALLS" ]] && ok "no external calls" || bad "external calls made: $(cat "$CALLS")"

say "test: happy path -> push + verify + SUCCESS line"
run_case
seed_snapshot
invoke env BULLPEN_OFFSITE_REMOTE="r2:bucket/backups"
RC=$?
[[ $RC -eq 0 ]] && ok "exit 0" || bad "exit $RC (wanted 0): $(cat "${SANDBOX}/out.log")"
grep -q "OFFSITE SUCCESS: auto_TEST objects=3 bytes=12345" "${SANDBOX}/out.log" \
  && ok "SUCCESS line with count+bytes" || bad "SUCCESS line wrong: $(grep SUCCESS "${SANDBOX}/out.log" || true)"
grep -q "rclone rcat r2:bucket/backups/auto_TEST/clickhouse.tar" "$CALLS" \
  && ok "clickhouse pushed as single tar object" || bad "clickhouse tar push missing/wrong"
grep -q "rclone copy ${SNAP}/auto_TEST_sqlite r2:bucket/backups/auto_TEST/sqlite" "$CALLS" \
  && ok "registry copied" || bad "registry copy missing"
grep -q "rclone check --one-way" "$CALLS" && ok "one-way verify ran" || bad "no verify"
if grep -q "rclone sync" "$CALLS"; then bad "SYNC used (delete authority!)"; else ok "copy only, never sync"; fi

say "test: tiny registry capture -> refuse to push, Discord pinged, exit 1"
run_case
seed_snapshot
head -c 100 /dev/zero > "${SNAP}/auto_TEST_sqlite/registry.sqlite"
invoke env BULLPEN_OFFSITE_REMOTE="r2:bucket/backups"
RC=$?
[[ $RC -eq 1 ]] && ok "exit 1" || bad "exit $RC (wanted 1)"
grep -q "too small" "${SANDBOX}/out.log" && ok "names the reason" || bad "reason missing"
grep -q "curl .*discord" "$CALLS" && ok "Discord alerted" || bad "no Discord alert"
if grep -qE "rclone (copy|rcat)" "$CALLS"; then bad "pushed despite bad registry"; else ok "nothing pushed"; fi

say "test: rclone copy failure -> exit 1 + Discord, local snapshot untouched by construction"
run_case
seed_snapshot
invoke env BULLPEN_OFFSITE_REMOTE="r2:bucket/backups" STUB_RCLONE_COPY_EXIT=3
RC=$?
[[ $RC -eq 1 ]] && ok "exit 1" || bad "exit $RC (wanted 1)"
grep -q "rclone copy failed" "${SANDBOX}/out.log" && ok "copy failure surfaced" || bad "failure not surfaced"
grep -q "curl .*discord" "$CALLS" && ok "Discord alerted" || bad "no Discord alert"

say ""
say "results: ${PASS} passed, ${FAIL} failed"
[[ $FAIL -eq 0 ]]
