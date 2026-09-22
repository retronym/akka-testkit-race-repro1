package repro;

import akka.javasdk.testkit.TestKit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cycles a TestKit runtime start/stop and prints the wall-clock duration of each half, for two
 * arms that differ only in whether Bootstrap.onStartup() drives sharded entity work.
 *
 * <p>Read the numbers. There is no assertion: the finding is the shape of the stop() distribution,
 * a flat baseline against occasional multi-second spikes, not a pass or fail.
 *
 * <p>Durations come from {@code nanoTime}, which does not advance while the machine sleeps. Run
 * the build under {@code caffeinate -d -i -s} anyway, since the build around it is long.
 */
public class BootstrapWorkTimingTest {

  private static final int ATTEMPTS = 15;
  // same count Bootstrap uses, so the two burst arms differ only in where the burst is issued from
  private static final int BURST_SIZE = 8;

  @Test
  public void withBurstFromOnStartup() {
    cycle("burst from onStartup()", "repro.onstartup-burst.enabled = true", false);
  }

  @Test
  public void withoutBurstFromOnStartup() {
    cycle("baseline, no burst", "repro.onstartup-burst.enabled = false", false);
  }

  /**
   * The control: the same burst, issued from here once {@code start()} has returned and the
   * cluster is already up, rather than from inside onStartup().
   */
  @Test
  public void withBurstAfterStartReturns() {
    cycle("burst after start() returns", "repro.onstartup-burst.enabled = false", true);
  }

  private static void cycle(String label, String hocon, boolean burstAfterStart) {
    var startDurations = new ArrayList<Duration>();
    var stopDurations = new ArrayList<Duration>();
    for (var attempt = 1; attempt <= ATTEMPTS; attempt++) {
      var testKit = new TestKit(TestKitPort.own(TestKit.Settings.DEFAULT, hocon));

      var startBegan = System.nanoTime();
      testKit.start();
      startDurations.add(Duration.ofNanos(System.nanoTime() - startBegan));

      if (burstAfterStart) {
        for (var i = 0; i < BURST_SIZE; i++) {
          testKit
            .getComponentClient()
            .forEventSourcedEntity(UUID.randomUUID().toString())
            .method(PassivationRaceEntity::start)
            .invoke("after-start-burst-" + i);
        }
      }

      var stopBegan = System.nanoTime();
      testKit.stop();
      stopDurations.add(Duration.ofNanos(System.nanoTime() - stopBegan));
      // no pause between cycles
    }

    System.out.println("=== BootstrapWorkTimingTest [" + label + "]: " + ATTEMPTS + " cycles ===");
    printStats("start()", startDurations);
    printStats("stop()", stopDurations);
  }

  private static void printStats(String label, List<Duration> durations) {
    var millis = durations.stream().map(Duration::toMillis).toList();
    var sum = millis.stream().mapToLong(Long::longValue).sum();
    var max = millis.stream().mapToLong(Long::longValue).max().orElse(0);
    var min = millis.stream().mapToLong(Long::longValue).min().orElse(0);
    System.out.println(
      label + " ms per cycle: " + millis
        + "  sum=" + sum + " min=" + min + " max=" + max + " mean=" + (sum / millis.size())
    );
  }
}
