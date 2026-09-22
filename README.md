# akka-testkit-race-repro

A standalone reproduction of a stall seen when an Akka SDK service does sharded
EventSourcedEntity work synchronously inside `ServiceSetup.onStartup()`, while the cluster and its
sharding are still forming. Filed upstream as akka-core#33001 and lightbend/akka-runtime#5719.

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

The stalled cycle carries this line, and it is the only warning in the run:

```
akka.cluster.sharding.ShardRegion - _timer: Graceful shutdown of shard region timed out,
region will be stopped. Remaining shards [], remaining buffered messages [1].
```

A shard region holds one message that was buffered before a shard home was assigned to it. The
region owns no shards, so nothing will ever deliver that message, and graceful shutdown waits its
whole timeout before giving up. The region is the runtime's own `_timer` region, not the region of
the entity the test drives.

## What the arms show

Run each arm on its own to keep one arm's cycles from warming up the next:

```bash
mvn test -Dtest='BootstrapWorkTimingTest#withBurstFromOnStartup' ...
```

Run that way, on the patched jarset:

```
[burst from onStartup()]
stop(): [9049, 222, 1256, 1266, 1243, 1246, 1248, 237, 217, 1247, 1236, 1246, 1239, 1237, 1250]

[baseline, no burst]
stop(): [141, 245, 1265, 1254, 225, 236, 1238, 247, 1238, 217, 227, 1258, 1258, 238, 1237]

[burst after start() returns]
stop(): [9051, 15, 14, 14, 6, 9, 11, 15, 9, 9, 14, 13, 12, 16, 9]
```

Two things separate here:

- The stall needs the burst, and it does not need `onStartup()`. Both burst arms stall once, and
  the arm that sends no commands at all never does. The graceful shutdown warning appears exactly
  once per JVM fork in every run that sends a burst, and never in a run that does not.
- Sending the burst from `onStartup()` does raise the ordinary stop() cost, from about 8
  milliseconds to a floor that alternates between about 220 milliseconds and about 1.25 seconds.
