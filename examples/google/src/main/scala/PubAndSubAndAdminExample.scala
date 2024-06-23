import com.anymindgroup.pubsub.google as G
import com.anymindgroup.pubsub.*
import zio.Console.printLine, zio.stream.ZStream, zio.*

object PubAndSubAndAdminExample extends ZIOAppDefault:
  def run = (for {
    // setup example topics and subscription
    _ <- ExamplesAdminSetup.run
    // run subscription while continually publishing in the background
    _ <- subStream.drainFork(pubStream).runDrain
  } yield ()).provide(publisherLayer, subscriberLayer)

  private val subStream =
    Subscriber
      .subscribe(ExamplesAdminSetup.exampleSub.name, Serde.int)
      .zipWithIndex
      .mapZIO { case ((message, ackReply), idx) =>
        for {
          _ <- printLine(
                 s"Received message ${idx + 1}"
                   + s" with id ${message.meta.messageId.value}"
                   + s" and data ${message.data}"
               )
          _ <- ackReply.ack()
        } yield ()
      }

  private val pubStream = ZStream
    .repeatZIOWithSchedule(Random.nextInt, Schedule.fixed(2.seconds))
    .mapZIO { sampleData =>
      for {
        id <- Publisher
                .publish[Any, Int](
                  PublishMessage(
                    data = sampleData,
                    attributes = Map.empty,
                    orderingKey = None,
                  )
                )
        _ <- printLine(s"Published message with id ${id.value}")
      } yield ()
    }

  val pubsubConnection: G.PubsubConnectionConfig =
    G.PubsubConnectionConfig.Emulator(
      G.PubsubConnectionConfig.GcpProject("any"),
      "localhost:8085",
    )

  val publisherLayer: TaskLayer[Publisher[Any, Int]] = ZLayer.scoped(
    G.Publisher.make(
      connection = pubsubConnection,
      topic = ExamplesAdminSetup.exampleTopic,
      enableOrdering = false,
    )
  )

  val subscriberLayer: TaskLayer[Subscriber] =
    ZLayer.scoped(G.Subscriber.makeStreamingPullSubscriber(pubsubConnection))
