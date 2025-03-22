import com.anymindgroup.pubsub.*, zio.*, zio.ZIO.*

object BasicSubscription extends ZIOAppDefault:
  // run a subscription stream based on Subscriber implementation provided
  def subStream(s: Subscriber) =
    s.subscribe(
      subscriptionName = SubscriptionName("gcp_project", "subscription"),
      deserializer = Serde.utf8String,
    ).mapZIO { (message, ackReply) =>
      for
        _ <- logInfo(
               s"Received message" +
                 s" with id ${message.messageId.value}" +
                 s" and data ${message.data}"
             )
        _ <- ackReply.ack()
      yield ()
    }.runDrain

  def run = {
    val makeSubscriber: RIO[Scope, Subscriber] =
      // make http based Subscriber implementation
      http.makeSubscriber(
        // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
        connection = PubsubConnectionConfig.Emulator("localhost", 8085)
      )
      // or similarly by using gRCP/StreamingPull API based implementation via Google's Java client:
      // google.makeStreamingPullSubscriber(
      //  connection = PubsubConnectionConfig.Emulator("localhost", 8085)
      // )

    makeSubscriber.flatMap(subStream)
  }
