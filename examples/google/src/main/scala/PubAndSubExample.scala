import com.anymindgroup.pubsub.google.{
  Publisher => GooglePublisher,
  PubsubConnectionConfig,
  Subscriber => GoogleSubscriber,
}
import com.anymindgroup.pubsub.pub.{PublishMessage, Publisher}
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.Subscriber

import zio.Console.printLine
import zio.stream.ZStream
import zio.{Random, Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer, durationInt}

object PubAndSubExample extends ZIOAppDefault {
  // consume up to given amount of messages and terminate
  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] = (for {
    consumeAmount <- ZIOAppArgs.getArgs.map(_.headOption.flatMap(_.toLongOption).getOrElse(10L))
    _             <- ExamplesAdminSetup.run.provide(Scope.default)
    _             <- subStream(consumeAmount).drainFork(pubStream).runDrain
    _             <- printLine(s"done consuming $consumeAmount")
  } yield ()).provideSome(publisherLayer, subscriberLayer)

  def subStream(consumeAmount: Long): ZStream[Subscriber, Throwable, Unit] =
    Subscriber
      .subscribe(ExamplesAdminSetup.exampleSub.name, Serde.int)
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

  val publisherLayer: ZLayer[Scope, Throwable, Publisher[Any, Int]] = ZLayer.fromZIO(
    GooglePublisher.make(
      connection = pubsubConnection,
      topic = ExamplesAdminSetup.exampleTopic,
      enableOrdering = false,
    )
  )

  val subscriberLayer: ZLayer[Scope, Throwable, Subscriber] = ZLayer.fromZIO(
    GoogleSubscriber.makeStreamingPullSubscriber(pubsubConnection)
  )
}
