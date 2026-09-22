package repro;

import akka.javasdk.testkit.TestKit;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Gives a TestKit runtime a port no other runtime in this JVM is using.
 *
 * <p>The testkit defaults every runtime to {@code akka.javasdk.testkit.http-port} 39390, and
 * {@code TestKit.stop()} returns before that socket is released. The next {@code start()} can then
 * fail to bind while its health check is answered by the runtime that is still shutting down, so
 * {@code start()} returns normally and the next call blocks against a terminating runtime. A port
 * per runtime removes that precondition, which matters here because this suite cycles
 * start/stop without pausing.
 *
 * <p>{@code Settings.withAdditionalConfig} replaces the additional config rather than merging into
 * it, so a caller hands its own config here instead of calling that method itself.
 */
public final class TestKitPort {

  private TestKitPort() {}

  /** Settings whose runtime binds a port nothing else in this JVM is using. */
  public static TestKit.Settings own(TestKit.Settings settings) {
    return own(settings, ConfigFactory.empty());
  }

  /** The same, for a caller with configuration of its own. */
  public static TestKit.Settings own(TestKit.Settings settings, String hocon) {
    return own(settings, ConfigFactory.parseString(hocon));
  }

  /** The same, for a caller that already has a parsed {@link Config}. */
  public static TestKit.Settings own(TestKit.Settings settings, Config extra) {
    return settings.withAdditionalConfig(
      extra
        .withFallback(
          ConfigFactory.parseString(
            "akka.javasdk.testkit.http-port = " + TestKit.availableLocalPort()
          )
        )
        .withFallback(settings.additionalConfig)
    );
  }
}
