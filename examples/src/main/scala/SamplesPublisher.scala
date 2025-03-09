import com.anymindgroup.pubsub.*, http.*
import zio.stream.*, zio.*, zio.ZIO.*

object SamplesPublisher extends ZIOAppDefault:
  def run =
    HttpPublisher
      .makeWithDefaultBackend(
        connection = PubsubConnectionConfig.Emulator("localhost", 8085),
        topicName = TopicName(projectId = "any", topic = "basic_example"),
        serializer = Serde.int,
      )
      .flatMap: publisher =>
        ZStream
          .repeatZIOWithSchedule(Random.nextInt, Schedule.fixed(2.seconds))
          .mapZIO { sample =>
            for {
              mId <- publisher.publish(
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
