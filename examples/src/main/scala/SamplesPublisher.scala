import com.anymindgroup.pubsub.*, zio.stream.*, zio.*, zio.ZIO.*

object SamplesPublisher extends ZIOAppDefault:
  def run = ZStream
    .repeatZIOWithSchedule(Random.nextInt, Schedule.fixed(2.seconds))
    .mapZIO { sample =>
      for {
        mId <- Publisher.publish[Any, Int](
                 PublishMessage(
                   data = sample,
                   attributes = Map.empty,
                   orderingKey = None,
                 )
               )
        _ <- logInfo(s"Published data $sample with message id ${mId.value}")
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
          connection = G.PubsubConnectionConfig.Emulator(
            G.PubsubConnectionConfig.GcpProject("any"),
            "localhost:8085",
          ),
          topicName = "basic_example",
          encoding = Encoding.Binary,
          enableOrdering = false,
        ),
        ser = Serde.int,
      )
    )
  }
