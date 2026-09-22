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

## Observed

One run on akka-javasdk 3.6.3, JDK 27, macOS, milliseconds per cycle:

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

A stop() of about 2.2 seconds against a floor of about 1.0 second follows an anomalously fast
start(), around 200 milliseconds against a normal 1.6 seconds, which is a start() that returned
before the cluster had settled. In this run that pairing appeared in the baseline arm as well as in
the onStartup arm, and the largest stop() was 2.2 seconds rather than the 10 seconds seen in the
service this was extracted from.
