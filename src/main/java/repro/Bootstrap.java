package repro;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives sharded entity work from inside {@link #onStartup()}, which is the condition under test.
 * The burst is a plain blocking command to {@link PassivationRaceEntity} repeated a few times,
 * issued while the cluster and its sharding are still forming.
 *
 * <p>The burst is configured rather than commented out so both arms run from one build. See
 * {@code src/main/resources/application.conf} and {@code BootstrapWorkTimingTest}.
 */
@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

  private final ComponentClient componentClient;
  private final Config config;

  public Bootstrap(ComponentClient componentClient, Config config) {
    this.componentClient = componentClient;
    this.config = config;
  }

  @Override
  public void onStartup() {
    if (!config.getBoolean("repro.onstartup-burst.enabled")) {
      log.info("onStartup burst disabled");
      return;
    }
    var size = config.getInt("repro.onstartup-burst.size");
    log.info("onStartup burst of {} starting", size);
    for (var i = 0; i < size; i++) {
      componentClient
        .forEventSourcedEntity(UUID.randomUUID().toString())
        .method(PassivationRaceEntity::start)
        .invoke("onstartup-burst-" + i);
    }
    log.info("onStartup burst of {} done", size);
  }
}
