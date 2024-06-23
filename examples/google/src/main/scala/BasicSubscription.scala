import com.anymindgroup.pubsub.*
import zio.*, zio.Console.printLine

object BasicSubscription extends ZIOAppDefault:
  def run = Subscriber
    .subscribe(subscriptionName = "basic_example", des = Serde.int)
    .mapZIO { (message, ackReply) =>
      for {
        _ <- printLine(s"Received message with id ${message.meta.messageId.value} and data ${message.data}")
        _ <- ackReply.ack()
      } yield ()
    }
    .runDrain
    .provide(googleSubscriber)

  // subscriber implementation
  private val googleSubscriber: TaskLayer[Subscriber] = {
    import com.anymindgroup.pubsub.google as G

    ZLayer.scoped(
      G.Subscriber.makeStreamingPullSubscriber(
        connection = G.PubsubConnectionConfig.Emulator(G.PubsubConnectionConfig.GcpProject("any"), "localhost:8085")
      )
    )
  }
