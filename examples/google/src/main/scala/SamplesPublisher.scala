import com.anymindgroup.pubsub.google.{Publisher as GooglePublisher, PublisherConfig, PubsubConnectionConfig}
import com.anymindgroup.pubsub.model.Encoding
import com.anymindgroup.pubsub.pub.{PublishMessage, Publisher}
import com.anymindgroup.pubsub.serde.Serde

import zio.stream.ZStream
import zio.{Console, Random, Schedule, Scope, ZIO, ZIOAppDefault, ZLayer, durationInt}

object SamplesPublisher extends ZIOAppDefault {
  override def run: ZIO[Scope, Any, Any] = ZStream
    .repeatZIOWithSchedule(
      Random.nextIntBetween(0, Int.MaxValue),
      Schedule.fixed(2.seconds),
    )
    .mapZIO { sampleData =>
      for {
        messageId <- Publisher.publish[Any, Int](
                       PublishMessage(
                         data = sampleData,
                         attributes = Map.empty,
                         orderingKey = None,
                       )
                     )
        _ <- Console.printLine(s"Published data $sampleData with message id ${messageId.value}")
      } yield ()

    }
    .runDrain
    .provideSome[Scope](publisher)

  private val publisher = ZLayer.fromZIO(
    GooglePublisher.make(
      config = PublisherConfig(
        connection = PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085"),
        topicName = "basic_example",
        encoding = Encoding.Binary,
        enableOrdering = false,
      ),
      ser = Serde.int,
    )
  )
}
