#!/usr/bin/env bash
# Runs the timing test, one arm per JVM, against the released jars or against a local patched
# build, and reports what each run printed.
#
#   ./harness.sh                                  # released jars, 2 runs of every arm
#   ./harness.sh --runs 3                         # more samples
#   ./harness.sh --patched /path/to/repo          # patched jars first, ~/.m2 as read-only tail
#   ./harness.sh --arm withBurstFromOnStartup     # one arm only
#   ./harness.sh --quiet                          # sharding debug logging off
#
# One arm per `mvn` invocation on purpose: cycles of one arm warm the JVM for whatever arm runs
# after it, and that changes the numbers.
#
# caffeinate wraps every run. -i alone does not block clamshell sleep, -s blocks system sleep on
# AC power, and -d keeps the display up so a long run is visibly alive.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

RUNS=2
PATCHED=""
ARMS=(withBurstFromOnStartup withoutBurstFromOnStartup withBurstAfterStartReturns)
LOG_LEVEL=DEBUG

while [ $# -gt 0 ]; do
  case "$1" in
    --runs) RUNS="$2"; shift 2 ;;
    --patched) PATCHED="$2"; shift 2 ;;
    --arm) ARMS=("$2"); shift 2 ;;
    --quiet) LOG_LEVEL=WARN; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

MVN_REPO=()
if [ -n "$PATCHED" ]; then
  if [ ! -d "$PATCHED" ]; then
    echo "no such repository: $PATCHED" >&2
    exit 2
  fi
  MVN_REPO=(-Dmaven.repo.local="$PATCHED" -Dmaven.repo.local.tail="$HOME/.m2/repository")
  echo "== patched: $PATCHED first, ~/.m2 as read-only tail =="
else
  echo "== released: ~/.m2 only =="
fi

OUT="logs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

# Which artifacts the numbers actually came from. Read this before trusting them.
mvn dependency:tree "${MVN_REPO[@]}" > "$OUT/dependency-tree.txt" 2>&1 || true
{ grep -E "akka-cluster|akka-projection-core|akka-runtime" "$OUT/dependency-tree.txt" || true; } \
  | sed 's/^\[INFO\] *//' | sort -u | tee "$OUT/versions.txt"

for arm in "${ARMS[@]}"; do
  for run in $(seq 1 "$RUNS"); do
    log="$OUT/$arm-run$run.log"
    echo "-- $arm run $run"
    caffeinate -d -i -s mvn test -Dtest="BootstrapWorkTimingTest#$arm" \
      -DargLine="-Dlogback.configurationFile=logback-runtime-dev-mode.xml -Dakka.javasdk.dev-mode.project-artifact-id=akka-testkit-race-repro -Drepro.akka.log.level=$LOG_LEVEL" \
      "${MVN_REPO[@]}" > "$log" 2>&1 \
      || echo "   run failed, see $log"
    grep "ms per cycle" "$log" | sed 's/^/   /'
    printf '   graceful shutdown timeouts: %s   shard home requests: %s\n' \
      "$(grep -c 'Graceful shutdown of shard region timed out' "$log" || true)" \
      "$(grep -c 'Requesting shard home for' "$log" || true)"
    grep -n 'Graceful shutdown of shard region timed out' "$log" | sed 's/^/   /' || true
  done
done

echo
echo "logs under $OUT (akka sharding logging at $LOG_LEVEL)"
