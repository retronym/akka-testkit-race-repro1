/*
 * Copyright (C) 2026-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.CoordinatedShutdown
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.testkit.AkkaSpec
import akka.testkit.TestActors.EchoActor
import akka.testkit.TestProbe
import akka.testkit.WithLogCapturing

object SingleNodeGracefulShutdownSpec {
  val config =
    """
    akka.loglevel = DEBUG
    akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
    akka.actor.provider = "cluster"
    akka.remote.artery.canonical.port = 0
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.cluster.sharding.verbose-debug-logging = on
    akka.cluster.sharding.remember-entities = on
    akka.cluster.sharding.remember-entities-store = eventsourced
    akka.cluster.sharding.state-store-mode = ddata
    """

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: Int => (msg.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: Int => msg.toString
    case _        => throw new IllegalArgumentException()
  }

  val ShardCount = 14
}

class SingleNodeGracefulShutdownSpec extends AkkaSpec(SingleNodeGracefulShutdownSpec.config) with WithLogCapturing {
  import SingleNodeGracefulShutdownSpec._

  "A single node cluster with remember-entities" must {
    "shut its shard regions down without waiting for shard home retries" in {
      val cluster = Cluster(system)
      cluster.join(cluster.selfAddress)
      awaitAssert(cluster.selfMember.status shouldEqual MemberStatus.Up, 10.seconds)

      val region = ClusterSharding(system).start(
        "type1",
        Props[EchoActor](),
        ClusterShardingSettings(system),
        extractEntityId,
        extractShardId)

      // One entity per shard, so several shards are deallocated concurrently on the way out.
      val probe = TestProbe()
      (1 to ShardCount).foreach { id =>
        region.tell(id, probe.ref)
        probe.expectMsg(10.seconds, id)
      }

      // Traffic that continues into the shutdown. A message arriving for a shard the coordinator
      // has just deallocated makes the region ask for that shard's home again.
      @volatile var keepSending = true
      val sender = new Thread(() => {
        while (keepSending) {
          (1 to ShardCount).foreach(id => region.tell(id, system.deadLetters))
          Thread.sleep(5)
        }
      })
      sender.setDaemon(true)
      sender.start()

      val started = System.nanoTime()
      Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)
      val elapsed = (System.nanoTime() - started).nanos
      keepSending = false

      info(s"CoordinatedShutdown took ${elapsed.toMillis}ms")
      // One node has no hand-over partner and nothing to rebalance, so nothing here has to wait.
      elapsed should be < 5.seconds
    }
  }
}
