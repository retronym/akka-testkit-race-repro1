package repro;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;

/**
 * A bare event sourced entity, one command pair and one event. It carries no business logic: the
 * reproduction needs a sharded entity and nothing else from it.
 */
@Component(id = "passivation-race-entity")
public class PassivationRaceEntity extends EventSourcedEntity<String, PassivationRaceEntity.Event> {

  public sealed interface Event {}

  @TypeName("passivation-race-started")
  public record Started(String value) implements Event {}

  public Effect<String> start(String value) {
    if (currentState() != null) {
      return effects().reply(currentState());
    }
    return effects().persist(new Started(value)).thenReply(state -> state);
  }

  public ReadOnlyEffect<String> get() {
    if (currentState() == null) {
      return effects().error("not started");
    }
    return effects().reply(currentState());
  }

  @Override
  public String applyEvent(Event event) {
    return switch (event) {
      case Started started -> started.value();
    };
  }
}
