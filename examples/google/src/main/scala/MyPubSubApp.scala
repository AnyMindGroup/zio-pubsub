package examples.google

import com.anymindgroup.pubsub.google
import com.anymindgroup.pubsub.google.Subscriber.makeStreamingPullSubscription
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig}
import com.anymindgroup.pubsub.model.{Encoding, SchemaSettings, Topic}
import com.anymindgroup.pubsub.pub.PublishMessage
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.Subscription

import zio.Console.printLine
import zio.stream.ZStream
import zio.{Random, Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer, durationInt}

object MyPubSubApp extends ZIOAppDefault {

  // run subscriber with publisher stream in the background
  override def run: ZIO[Scope & ZIOAppArgs, Any, Any] = (for {
    consumeAmount <- ZIOAppArgs.getArgs.map(_.headOption.flatMap(_.toIntOption).getOrElse(10))
    subStream     <- makeSubStream(consumeAmount)
    _             <- subStream.drainFork(samplesPubStream).runDrain
    _             <- printLine(s"done consuming $consumeAmount")
  } yield ()).provideSome(pubsubConnection, examplesSetup)

  private val exampleTopic = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  private val exampleSub = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
  )

  // creates sample topic and subscription if they don't exist yet
  private val examplesSetup = ZLayer.fromZIO(
    PubsubAdmin.setup(List(exampleTopic), List(exampleSub))
  )

  private def makeSubStream(amount: Int) =
    for {
      connection <- ZIO.service[PubsubConnectionConfig]
      stream     <- makeStreamingPullSubscription(connection, exampleSub.name, Serde.int)
    } yield stream.zipWithIndex.mapZIO { case ((message, ackReply), idx) =>
      for {
        _ <- printLine(
               s"Received message ${idx + 1} / ${amount}"
                 + s" with id ${message.meta.messageId.value}"
                 + s" and data ${message.data}"
             )
        _ <- ackReply.ack()
      } yield ()
    }.take(amount.toLong)

  // publish random integer in an interval
  private val samplesPubStream = for {
    connection <- ZStream.service[PubsubConnectionConfig]
    publisher <- ZStream.fromZIO(
                   google.Publisher.make(
                     connection = connection,
                     topic = exampleTopic,
                     enableOrdering = false,
                   )
                 )
    _ <- ZStream
           .repeatZIOWithSchedule(
             Random.nextIntBetween(0, Int.MaxValue),
             Schedule.fixed(2.seconds),
           )
           .mapZIO { sampleData =>
             publisher.publishEvent(
               PublishMessage(
                 data = sampleData,
                 attributes = Map.empty,
                 orderingKey = None,
               )
             )
           }
  } yield ()

  private val pubsubConnection = ZLayer.succeed(
    PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085")
  )
}
