package examples.google

import com.anymindgroup.pubsub.google
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig, Subscriber}
import com.anymindgroup.pubsub.model.{Encoding, SchemaSettings, Topic}
import com.anymindgroup.pubsub.pub.PublishMessage
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.Subscription

import zio.Console.printLine
import zio.stream.ZStream
import zio.{RIO, Random, Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer, durationInt}

object MyPubSubApp extends ZIOAppDefault {

  override def run: ZIO[Scope & ZIOAppArgs, Any, Any] = (for {
    consumeAmount      <- ZIOAppArgs.getArgs.map(_.headOption.flatMap(_.toIntOption).getOrElse(10))
    subscriptionStream <- makeSubscriptionStream(consumeAmount)
    _                  <- subscriptionStream.drainFork(samplesPublishStream).runDrain // run publisher in the background
  } yield ()).provideSome(pubsubConnection, examplesSetup)

  private val exampleTopic = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  private val exampleSubscription = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
  )

  // creates sample topic and subscription if they don't exist yet
  private val examplesSetup = ZLayer.fromZIO(PubsubAdmin.setup(List(exampleTopic), List(exampleSubscription)))

  private def makeSubscriptionStream(amount: Int): RIO[PubsubConnectionConfig & Scope, ZStream[Any, Throwable, Unit]] =
    for {
      connection <- ZIO.service[PubsubConnectionConfig]
      stream     <- Subscriber.makeStreamingPullSubscription(connection, exampleSubscription.name, Serde.int)
    } yield stream.zipWithIndex.mapZIO { case ((message, ackReply), idx) =>
      for {
        _ <-
          printLine(
            s"Received message ${idx + 1} / ${amount} with id ${message.meta.messageId.value} and data ${message.data}"
          )
        _ <- ackReply.ack()
      } yield ()
    }.take(amount.toLong)

  // publish random integer in an interval
  private val samplesPublishStream: ZStream[Scope & PubsubConnectionConfig, Throwable, Unit] = for {
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
