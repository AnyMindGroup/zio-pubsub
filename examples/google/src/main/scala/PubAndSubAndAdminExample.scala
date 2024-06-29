import com.anymindgroup.pubsub.google as G
import com.anymindgroup.pubsub.*
import zio.Console.printLine, zio.stream.*, zio.*

object PubAndSubAndAdminExample extends ZIOAppDefault:
  def program = for {
    // ensure example topics and subscription are setup
    _ <- G.PubsubAdmin.setup(pubsubConnection, List(exampleTopic), List(exampleSubsription))
    // run subscription while continually publishing in the background
    _ <- subStream.drainFork(pubStream).runDrain
  } yield ()

  // topic description
  val exampleTopic: Topic[Any, Int] = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  // subscription description
  val exampleSubsription: Subscription = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
    deadLettersSettings = None,
  )

  // subscription stream for existing subscription
  val subStream: ZStream[Subscriber, Throwable, Unit] =
    Subscriber
      .subscribe(exampleSubsription.name, Serde.int)
      .mapZIO { case (msg, ackReply) =>
        for {
          _ <- printLine(
                 s"Received message with id ${msg.meta.messageId.value}"
                   + s" and data ${msg.data}"
               )
          _ <- ackReply.ack()
        } yield ()
      }

  // publish random integer every 2 seconds
  val pubStream: ZStream[Publisher[Any, Int], Throwable, Unit] =
    ZStream
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
      project = G.PubsubConnectionConfig.GcpProject("any"),
      host = "localhost:8085",
    )

  // int publisher implementation
  val publisherLayer: TaskLayer[Publisher[Any, Int]] = ZLayer.scoped(
    G.Publisher.make(
      connection = pubsubConnection,
      topic = exampleTopic,
      enableOrdering = false,
    )
  )

  // subscriber implementation
  val subscriberLayer: TaskLayer[Subscriber] =
    ZLayer.scoped(G.Subscriber.makeStreamingPullSubscriber(pubsubConnection))

  def run = program.provide(publisherLayer, subscriberLayer)
