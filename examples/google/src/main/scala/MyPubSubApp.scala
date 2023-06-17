package examples.google

import com.anymindgroup.pubsub.google
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig}
import com.anymindgroup.pubsub.model.{Encoding, SchemaSettings, Topic}
import com.anymindgroup.pubsub.pub.{PublishMessage, Publisher}
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.{Subscriber, Subscription}

import zio.Console.printLine
import zio.stream.ZStream
import zio.{Random, Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer, durationInt}

object MyPubSubApp extends ZIOAppDefault {
  type Env = Scope & ZIOAppArgs

  override def run: ZIO[Env, Any, Any] = (for {
    consumeAmount <- ZIOAppArgs.getArgs.map(_.headOption.flatMap(_.toLongOption).getOrElse(10L))
    _             <- subStream(consumeAmount).drainFork(pubStream).runDrain
    _             <- printLine(s"done consuming $consumeAmount")
  } yield ()).provideSome[Env](examplesSetup, publisherLayer, subscriberLayer)

  def subStream(consumeAmount: Long): ZStream[Subscriber, Throwable, Unit] =
    Subscriber
      .subscribe(exampleSub.name, Serde.int)
      .zipWithIndex
      .mapZIO { case ((message, ackReply), idx) =>
        for {
          _ <- printLine(
                 s"Received message ${idx + 1} / ${consumeAmount}"
                   + s" with id ${message.meta.messageId.value}"
                   + s" and data ${message.data}"
               )
          _ <- ackReply.ack()
        } yield ()
      }
      .take(consumeAmount)

  val pubStream: ZStream[Publisher[Any, Int], Throwable, Unit] = ZStream
    .repeatZIOWithSchedule(
      Random.nextIntBetween(0, Int.MaxValue),
      Schedule.fixed(2.seconds),
    )
    .mapZIO { sampleData =>
      Publisher.publish[Any, Int](
        PublishMessage(
          data = sampleData,
          attributes = Map.empty,
          orderingKey = None,
        )
      )
    }
    .drain

  val pubsubConnection: PubsubConnectionConfig =
    PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085")

  val exampleTopic: Topic[Any, Int] = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  val exampleSub: Subscription = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
  )

  val examplesSetup: ZLayer[Any, Throwable, Unit] = ZLayer.fromZIO(
    PubsubAdmin.setup(pubsubConnection, List(exampleTopic), List(exampleSub))
  )

  val publisherLayer: ZLayer[Scope, Throwable, Publisher[Any, Int]] = ZLayer.fromZIO(
    google.Publisher.make(
      connection = pubsubConnection,
      topic = exampleTopic,
      enableOrdering = false,
    )
  )

  val subscriberLayer: ZLayer[Scope, Throwable, Subscriber] = ZLayer.fromZIO(
    google.Subscriber.makeStreamingPullSubscriber(pubsubConnection)
  )
}
