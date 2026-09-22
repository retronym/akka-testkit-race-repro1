# akka-testkit-race-repro

A standalone reproduction of a stall in TestKit runtime shutdown. A service that sends a burst of
commands to a sharded EventSourcedEntity can leave one message buffered in a shard region that
never gets a shard home, and stopping the runtime then waits about nine seconds for it. Sending
that burst from `ServiceSetup.onStartup()` makes the stall common rather than rare, because
`start()` does not wait for the hook and the commands are still in flight when the caller stops
the runtime. Related upstream issues: akka-core#33001 and lightbend/akka-runtime#5719.

The stall is only visible against Akka jars carrying the fixes listed under "Observed on a patched
jarset". On the released jars an ordinary stop() takes about a second, which hides it. A further
change to `ShardRegion`, built on top of those, removes the stall; see the last section.

The project carries no business logic. It holds one bare entity, three projections over it, a
`ServiceSetup` whose startup hook sends a small burst of commands to that entity, and one test that
cycles the TestKit runtime and prints how long each start and each stop took.

## Components

| Class | What it is for |
|---|---|
| `repro.PassivationRaceEntity` | A bare `EventSourcedEntity<String, Event>` with one start/get command pair |
| `repro.PassivationRaceView` | A view over the entity's events. The query is never called |
| `repro.PassivationRaceConsumers` | Two consumers over the same events, handlers that ignore them |
| `repro.Bootstrap` | The `ServiceSetup` whose `onStartup()` sends the burst |

The view and the two consumers give the entity three sharded daemon process coordinators rather
than one, which is the fan-out a real service reaches quickly. A coordinator is created per
registered projection whatever its handler does with an event, so the handlers here are empty.

## The test

`repro.BootstrapWorkTimingTest` runs three arms, fifteen start/stop cycles each, and prints the
wall-clock duration of every start and every stop:

- `withBurstFromOnStartup` sends the burst from inside `onStartup()`.
- `withoutBurstFromOnStartup` is the baseline. No burst is sent.
- `withBurstAfterStartReturns` sends the same burst from the test, once `start()` has returned and
  the cluster is already up.

The arms differ only in whether the burst runs and where it is issued from. Which arm a run uses is
configuration, `repro.onstartup-burst.enabled` in `src/main/resources/application.conf`, so one
build runs all three.

There is no assertion. Read the printed numbers. The finding is the shape of the stop()
distribution, a flat baseline against occasional multi-second spikes, not a pass or a fail.

## Build and run

The Akka artifacts are not on Maven Central. Resolving them needs the Akka repository, whose URL is
obtained from <https://account.akka.io/token>.

```bash
mvn test -Dtest=BootstrapWorkTimingTest
```

Run it under `caffeinate -d -i -s` on macOS. The printed durations come from `nanoTime`, which does
not advance while the machine sleeps, but the run is long enough to be worth protecting.

To see the shard lookups around a stalled cycle, keep the output and count them:

```bash
caffeinate -d -i -s mvn test -Dtest=BootstrapWorkTimingTest > run.log 2>&1
grep -c "Requesting shard home for" run.log
```

A per-cycle shard lookup is normal. The stop() duration is what to read.

## Running against a patched jarset

The numbers below separate into two groups depending on which Akka build the test runs against.
To run against a local build of the Akka jars, put them in a repository of their own and use it as
the head of a split repository, leaving the shared one as the tail:

```bash
mvn test -Dtest=BootstrapWorkTimingTest \
  -Dmaven.repo.local=/path/to/patched/repo \
  -Dmaven.repo.local.tail="$HOME/.m2/repository"
```

Confirm which artifacts the run resolved before reading any numbers:

```bash
mvn dependency:tree -Dmaven.repo.local=... -Dmaven.repo.local.tail=... | grep akka-cluster
```

## Observed on the released jars

akka-javasdk 3.6.3 with the artifacts it resolves from Maven, JDK 27, macOS. Milliseconds per
cycle:

```
[baseline, no burst]
start(): [3235, 1717, 1671, 223, 1664, 232, 1647, 1667, 1663, 1658, 1656, 1642, 1643, 1633, 1644]
stop():  [1090, 1035, 1036, 1185, 1034, 2198, 1026, 1026, 1023, 1025, 1027, 1034, 1028, 1025, 1025]

[burst after start() returns]
start(): [1627, 193, 1636, 1633, 1631, 1633, 1632, 1650, 1658, 1631, 1626, 1641, 1662, 1642, 1638]
stop():  [1042, 1027, 1031, 1026, 1030, 1024, 1027, 1022, 1022, 1029, 1024, 1031, 1028, 1030, 1026]

[burst from onStartup()]
start(): [1634, 1643, 1645, 1638, 1649, 1635, 172, 1644, 1623, 1631, 1627, 1620, 1632, 1648, 1639]
stop():  [1025, 1025, 1034, 1029, 1025, 1025, 2198, 1026, 1025, 1026, 1027, 1035, 1032, 1028, 1027]
```

Every stop() sits near one second and the longest is 2.2 seconds. That floor is high enough to hide
a longer stall, and the 2.2 second outlier appears in the baseline arm too, so these numbers do not
separate the arms.

## Observed on a patched jarset

The same test against a local build carrying akka-core #32997, #32998 and #33000, akka-projection
#1457 and akka-runtime #5718, resolved as akka-runtime 1.6.15, akka-cluster 2.10.20 and
akka-projection 1.6.20. Two runs of all three arms:

```
[baseline, no burst]
stop() run 1: [163, 1257, 1265, 1247, 226, 1249, 1237, 1247, 228, 217, 1225, 1248, 1248, 218, 1238]
stop() run 2: [1163, 226, 226, 1257, 1247, 245, 1269, 1244, 1237, 237, 236, 226, 1248, 1235, 1250]

[burst after start() returns]
stop() run 1: [7, 7, 5, 8, 5, 5, 12, 10, 11, 7, 10, 6, 12, 16, 5]
stop() run 2: [11, 10, 10, 12, 9, 5, 10, 13, 4, 5, 12, 6, 9, 7, 13]

[burst from onStartup()]
stop() run 1: [1238, 1249, 9027, 1240, 1237, 1248, 1258, 1248, 1247, 217, 228, 1238, 1247, 1246, 1238]
stop() run 2: [1246, 227, 9028, 249, 1246, 1238, 1238, 1249, 219, 238, 218, 228, 1237, 1278, 1238]
```

The patched jars take the ordinary stop() down to single digit milliseconds, which is what makes
the stall visible: a cycle of about 9.03 seconds against a floor of about 8 milliseconds, at the
same cycle in both runs.

## What the stall is

The snippets below are from one stalled cycle of `withBurstFromOnStartup`, at debug level, in
`logs/20260922-230636/withBurstFromOnStartup-run1.log`. The runs behind every number in this file
are committed under `logs/`, one directory per harness invocation, each with the dependency tree
the run resolved. Timestamps are kept so the gaps are readable.

The hook is not awaited. The burst starts, `start()` returns while it is still issuing, and the
test calls `stop()` about ten milliseconds later:

```
23:06:42.365 INFO  akka.runtime.DiscoveryManager - Akka Runtime started at 127.0.0.1:53267
23:06:42.366 INFO  repro.Bootstrap - onStartup burst of 8 starting
23:06:42.474 INFO  akka.javasdk.testkit.TestKit - Runtime started
23:06:42.485 DEBUG a.c.sharding.DDataShardCoordinator - passivation-race-entity: Graceful shutdown of region [...] with [1] shards
```

`onStartup burst of 8 done` is logged zero times in the run, against fifteen `starting` lines. No
cycle ever finishes its burst.

One command had reached a shard. The rest were still in flight when shutdown began, and one of
them asks for a shard home after the region has already started shutting down:

```
23:06:42.384 DEBUG a.c.sharding.DDataShardCoordinator - passivation-race-entity: Shard [257] allocated at [...]
23:06:42.485 INFO  a.c.sharding.DDataShardCoordinator - passivation-race-entity: Starting shutting down shards [257] due to region shutting down or explicit stopping of shards.
23:06:42.527 DEBUG akka.cluster.sharding.ShardRegion - passivation-race-entity: Request shard [427] home. Coordinator [Some(...)]
```

Shard 427 is never allocated. The coordinator logs nothing further about it, and the region retries
every two seconds, holding the one message it cannot deliver:

```
23:06:44.128 DEBUG akka.cluster.sharding.ShardRegion - passivation-race-entity: Requesting shard home for [427] from coordinator at [...]. [1] buffered messages.
23:06:46.149 DEBUG akka.cluster.sharding.ShardRegion - passivation-race-entity: Requesting shard home for [427] ... [1] buffered messages.
23:06:48.169 DEBUG akka.cluster.sharding.ShardRegion - passivation-race-entity: Requesting shard home for [427] ... [1] buffered messages.
23:06:50.188 DEBUG akka.cluster.sharding.ShardRegion - passivation-race-entity: Requesting shard home for [427] ... [1] buffered messages.
23:06:51.499 WARN  akka.cluster.sharding.ShardRegion - passivation-race-entity: Graceful shutdown of shard region timed out, region will be stopped. Remaining shards [], remaining buffered messages [1].
```

That is the stalled cycle: about nine seconds from the shutdown request to the region giving up,
against about eight milliseconds when nothing is buffered. Shutdown then proceeds normally:

```
23:06:51.499 DEBUG akka.cluster.sharding.ShardRegion - passivation-race-entity: Region stopped
23:06:51.500 DEBUG akka.actor.CoordinatedShutdown - Performing phase [cluster-leave] with [1] tasks.
```

The command that was buffered fails the hook, with the error the service under investigation
reported:

```
23:06:55.001 ERROR repro.Bootstrap - onStartup() threw an exception
java.util.concurrent.TimeoutException: Command to entity [passivation-race-entity] id [d9031920-...] timed out.
```

So the sequence is: a command arrives for a shard that has no home yet, the region buffers it and
asks the coordinator, shutdown of that region begins before the answer comes, the answer never
comes, and graceful shutdown waits out its timeout for a message that cannot be delivered.

`onStartup()` makes this common because `start()` does not wait for the hook. The burst is still
issuing when the caller believes startup is finished, so a caller that stops the runtime promptly
stops it with commands in flight. A burst issued after `start()` returns is fully delivered before
`stop()` is called, which is why that arm rarely stalls.

## What the runs support

Every sample below comes from `./harness.sh` against the patched jarset, one arm per JVM. A stall
is a cycle whose `stop()` exceeded nine seconds; each one is accompanied by exactly one of the
warnings above, and no run produced that warning without a stall.

| Arm | Runs | Cycles | Stalled cycles |
|---|---|---|---|
| burst from `onStartup()` | 6 | 90 | 7, in 5 of the 6 runs |
| burst after `start()` returns | 7 | 105 | 1, in 1 of the 7 runs |
| baseline, no burst | 6 | 90 | 0 |

The claim those numbers support is narrower than "onStartup is required":

- **The burst is necessary.** No run that sent no commands stalled, in 90 cycles.
- **Sending the burst from `onStartup()` makes the stall common rather than rare.** It appeared in
  5 of 6 runs there, against 1 of 7 when the same burst went out after `start()` had returned.
  Issuing the work after the cluster is up reduces the stall, it does not remove it.
- **`onStartup()` also raises the ordinary stop() cost.** With the burst there, stop() alternates
  between about 230 milliseconds and about 1.25 seconds. With the burst after start, every
  unstalled cycle is between 6 and 132 milliseconds.

The one stall in the after-start arm named a different region, `_timer`, where every stall in the
`onStartup()` arm named `passivation-race-entity`. One sample is not enough to call that a
distinction.

The shard home lookups are not the signal. At debug level a run logs between 45 and 74 of
`Requesting shard home for`, in stalled and unstalled runs alike. Read the stop() distribution and
the graceful shutdown warning instead.

Debug logging is itself a timing change, so both levels were sampled. The table mixes them: 2 runs
per arm at debug, 4 at warn, and the arms separate the same way at either level.

## Logging

The runtime's dev-mode logback config sets the whole `akka` logger to WARN. This project raises
sharding, singleton and coordinated shutdown to DEBUG through `include-dev-loggers.xml`, which that
config includes last. `./harness.sh --quiet` puts them back to WARN for a run.

## Where the root cause is likely to be

The first three points below are a reading of the evidence above. The fourth was then tested, and
it holds. Line numbers are
from akka-core at v2.10.20 plus the three fixes that this jarset carries, commit 749f432 on branch
`upstream-fixes-2.10.20`.

**The nine seconds are a known timeout, not a hang.** `ShardRegion.scala:1017` arms
`GracefulShutdownTimeout` at the `cluster-sharding-shutdown-region` phase timeout minus one second.
That phase defaults to ten seconds in `akka-actor/src/main/resources/reference.conf`, which gives
nine, and every stalled cycle measured between 9027 and 9072 milliseconds. Nothing is deadlocked.
The region is waiting exactly as long as it was told to.

**The coordinator drops the request that the region is waiting for.** In
`ShardCoordinator.scala:917`, `GetShardHome` for an unknown shard computes
`(state.regions -- gracefulShutdownInProgress) -- regionTerminationInProgress`, and allocates only
`if (activeRegions.nonEmpty)`. On one node whose only region has asked to shut down, that set is
empty, so the branch falls through with no reply, no failure and no log line. This matches the log
exactly: the region asks for shard 427 four times over eight seconds, and the coordinator says
nothing about 427 at all. The region cannot tell "not yet" from "never", so it retries until its
timer fires.

**The region then waits for a message that can never be delivered.**
`tryCompleteGracefulShutdownIfInProgress` at `ShardRegion.scala:1172` completes the shutdown only
when `gracefulShutdownInProgress && shards.isEmpty && shardBuffers.isEmpty`. One buffered message is enough to hold the whole region open for the
full nine seconds, and at the end that message is dropped anyway. The wait buys nothing on a single
node.

That is the most likely root cause: the coordinator's silence on an unanswerable `GetShardHome`
and the region's willingness to wait out a full phase timeout for it are two halves of the same
gap. A single node is where it shows, because a single node is where "no other member can take this
shard" is certain.

**The candidate fix removes it.** The akka-core working tree on branch `upstream-fixes-2.10.20`
carried an uncommitted change to `ShardRegion.scala` that had never been built into a jar, so every
number above was measured against the released sharding artifact. Built and published into the same
repository, it changes both halves of the wait:

```scala
   private def tryCompleteGracefulShutdownIfInProgress(): Unit =
-    if (gracefulShutdownInProgress && shards.isEmpty && shardBuffers.isEmpty) {
-      log.debug("{}: Completed graceful shutdown of region.", typeName)
-      context.stop(self) // all shards have been rebalanced, complete graceful shutdown
+    if (gracefulShutdownInProgress && shards.isEmpty) {
+      if (shardBuffers.isEmpty) {
+        log.debug("{}: Completed graceful shutdown of region.", typeName)
+        context.stop(self) // all shards have been rebalanced, complete graceful shutdown
+      } else if (isOnlyMember) {
+        // No other member can host these shards, so the coordinator can never answer the
+        // GetShardHome requests the buffers are waiting for. Waiting for the graceful shutdown
+        // timeout drops the same messages a phase timeout later.
+        log.debug(
+          "{}: Completed graceful shutdown of region, dropping [{}] buffered messages that no " +
+          "other member can take over.",
+          typeName,
+          shardBuffers.totalSize)
+        dropShardBuffers()
+        context.stop(self)
+      }
     }

+  // No other member, in any status, that could host a shard of this region.
+  private def isOnlyMember: Boolean =
+    cluster.state.members.forall(_.uniqueAddress == cluster.selfUniqueAddress)

   def bufferMessage(shardId: ShardId, msg: Any, snd: ActorRef) = {
     val totBufSize = shardBuffers.totalSize
-    if (totBufSize >= bufferSize) {
+    if (gracefulShutdownInProgress && isOnlyMember) {
+      // This region is going away and no other member can take the shard over, so buffering
+      // would only wait for a shard home that never comes.
+      log.debug("{}: Region is shutting down, dropping message for shard [{}]", typeName, shardId)
+      context.system.deadLetters ! msg
+      instrumentation.messageDropped(typeName)
+    } else if (totBufSize >= bufferSize) {
```

Four quiet runs of the `onStartup()` arm against that jar, in `logs/20260922-233638`:

```
stop() run 1: [154, 31, 19, 13, 13, 15, 14, 13, 33, 10, 14, 26, 36, 14, 16]
stop() run 2: [102, 22, 13, 32, 23, 14, 11, 24, 15, 15, 11, 15, 24, 15, 32]
stop() run 3: [98, 19, 51, 45, 23, 21, 76, 20, 16, 33, 19, 14, 13, 13, 13]
stop() run 4: [144, 24, 14, 14, 14, 14, 25, 12, 12, 6, 15, 7, 5, 7, 4]
```

The prediction holds, and more than the stall goes with it:

- No stalled cycle in 60, against 7 in 90 on the same arm without this jar.
- The graceful shutdown warning does not appear in any run.
- The bimodal floor of about 230 milliseconds and about 1.25 seconds is gone. Every cycle is
  between 4 and 154 milliseconds, which is the range the after-start arm already had.
- In a debug run of the same arm, `logs/20260922-234113`, the entity region logs no shard home
  request and no buffered message at all, and `onStartup()` no longer fails with
  `TimeoutException: Command to entity ... timed out`. The region hands its one shard off in about
  eighty milliseconds and stops.

The two jarsets differ only in this artifact, so the comparison is that change alone. The drop
paths the diff adds are not reached in these runs: the stall is gone because nothing ends up
buffered against a shutting-down region, not because the buffer is emptied.

**One layer above, the service side deserves its own question.** `start()` returns while
`onStartup()` is still issuing commands, so a caller that stops the runtime promptly stops it with
work in flight, and the hook then fails with `TimeoutException: Command to entity ... timed out`.
Whether a startup hook should be awaited, and what a caller is entitled to assume when `start()`
returns, is a question for the SDK and runtime rather than for sharding. Fixing the sharding side
removes the nine seconds. It does not make the in-flight commands succeed.

**What is not the cause.** Shard home lookups are ordinary traffic on the jarset that stalls: 45 to
74 per run, in stalled and clean runs alike. Wide fan-out is not required either, since one view and two consumers are
enough. Neither is the entity's own work, which is one event.
