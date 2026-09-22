package repro;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;

/**
 * One of the three projections over {@link PassivationRaceEntity}. A projection on an entity makes
 * the runtime create a sharded daemon process coordinator for it, which a bare entity, however
 * many instances are created, does not have. The query is never called; this exists so the
 * coordinator gets created.
 */
@Component(id = "passivation-race-view")
public class PassivationRaceView extends View {

  public record Row(String value) {}

  @Consume.FromEventSourcedEntity(PassivationRaceEntity.class)
  public static class PassivationRaceUpdater extends TableUpdater<Row> {
    public Effect<Row> onEvent(PassivationRaceEntity.Event event) {
      return switch (event) {
        case PassivationRaceEntity.Started started -> effects().updateRow(new Row(started.value()));
      };
    }
  }

  @Query("SELECT * FROM passivation_race_view WHERE value = :value")
  public QueryEffect<Row> byValue(String value) {
    return queryResult();
  }
}
