package repro;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.consumer.Consumer;

/**
 * Two more projections over {@link PassivationRaceEntity}, which together with the view give the
 * entity a fan-out of three sharded daemon process coordinators rather than one. The handlers do
 * nothing: a coordinator is created per registered projection regardless of what its handler does
 * with an event.
 */
public final class PassivationRaceConsumers {

  private PassivationRaceConsumers() {}

  @Component(id = "passivation-race-consumer-a")
  @Consume.FromEventSourcedEntity(PassivationRaceEntity.class)
  public static class ConsumerA extends Consumer {
    public Effect onEvent(PassivationRaceEntity.Event event) {
      return effects().ignore();
    }
  }

  @Component(id = "passivation-race-consumer-b")
  @Consume.FromEventSourcedEntity(PassivationRaceEntity.class)
  public static class ConsumerB extends Consumer {
    public Effect onEvent(PassivationRaceEntity.Event event) {
      return effects().ignore();
    }
  }
}
