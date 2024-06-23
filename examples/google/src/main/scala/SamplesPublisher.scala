import com.anymindgroup.pubsub.*
import zio.stream.ZStream, zio.*

object SamplesPublisher extends ZIOAppDefault:
  def run = ZStream
    .repeatZIOWithSchedule(Random.nextInt, Schedule.fixed(2.seconds))
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
    .provide(intPublisher)

  // int publisher implementation
  private val intPublisher: TaskLayer[Publisher[Any, Int]] = {
    import com.anymindgroup.pubsub.google as G

    ZLayer.scoped(
      G.Publisher.make(
        config = G.PublisherConfig(
          connection = G.PubsubConnectionConfig.Emulator(G.PubsubConnectionConfig.GcpProject("any"), "localhost:8085"),
          topicName = "basic_example",
          encoding = Encoding.Binary,
          enableOrdering = false,
        ),
        ser = Serde.int,
      )
    )
  }
