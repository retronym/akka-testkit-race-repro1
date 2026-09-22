#!/usr/bin/env bash
# Publishes akka-cluster + akka-cluster-tools + akka-cluster-sharding at a given akka-core ref
# into a tier1 repo of its own, under .mvn-bisect/<name>, leaving akka-projection and akka-runtime
# unpublished so they resolve from ~/.m2 (released jars) as the tail. Used to bisect which of
# #32997 / #32998 / #33000 (in akka-cluster / akka-cluster-tools) are needed to reveal the
# akka-core#33001 stall, and to test the candidate ShardRegion.scala fix (in
# akka-cluster-sharding), independent of the akka-projection#1457 and akka-runtime#5718 fixes the
# original "patched jarset" also carried.
#
# All three modules are published on every call, including combos that only touch akka-cluster,
# because the first version of this script published only akka-cluster and akka-cluster-tools:
# every "plus the candidate fix" combo built with it silently fell back to the stock, unpatched
# akka-cluster-sharding jar from ~/.m2, and every retest of the fix run against one of those
# combos was comparing the stock jar against itself. See the README's "Which patch the stall
# needs" section for what that produced and how it was found.
#
#   .mvn-bisect/build-core-combo.sh <git-ref> <name>
#
# <git-ref> is any ref/sha in ~/code/akka-core. <name> becomes .mvn-bisect/<name>, the tier1 head
# to pass to ../harness.sh --patched.
set -euo pipefail
REF="$1"
NAME="$2"

BISECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIER1="$BISECT_ROOT/$NAME"
AKKA_CORE_CLONE="$HOME/code/akka-core"
SBT_GLOBAL_BASE="/Users/jz/code/worktrees/.mvn/sbt-global"

mkdir -p "$TIER1" "$BISECT_ROOT/logs"

echo "== akka-core @ $REF -> $TIER1 =="
(
  cd "$AKKA_CORE_CLONE"
  git checkout -q --detach "$REF"
  git log -1 --oneline
)

(
  cd "$AKKA_CORE_CLONE"
  sbt -batch -Dakka.no.discipline=true -Dmaven.repo.local="$TIER1" -Dsbt.global.base="$SBT_GLOBAL_BASE" \
    'set every version := "2.10.20"' \
    akka-cluster/publishM2 akka-cluster-tools/publishM2 akka-cluster-sharding/publishM2
) 2>&1 | tee "$BISECT_ROOT/logs/build-$NAME.log" | tail -20

echo "published $NAME"
