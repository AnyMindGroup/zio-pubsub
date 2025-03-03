import com.anymindgroup.pubsub.*, zio.*, zio.ZIO.*

object BasicSubscription extends ZIOAppDefault:
  def run = Subscriber
    .subscribe(subscriptionName = "basic_example", des = Serde.int)
    .mapZIO { (message, ackReply) =>
      for {
        _ <- logInfo(
               s"Received message" +
                 s" with id ${message.meta.messageId.value}" +
                 s" and data ${message.data}"
             )
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
        connection = PubsubConnectionConfig.Emulator(
          PubsubConnectionConfig.GcpProject("any"),
          "localhost:8085",
        )
      )
    )
  }
