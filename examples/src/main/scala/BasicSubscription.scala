import com.anymindgroup.pubsub.*, http.*
import zio.*, zio.ZIO.*

object BasicSubscription extends ZIOAppDefault:
  def run = HttpSubscriber
    .makeWithDefaultBackend(
      connection = PubsubConnectionConfig.Emulator("localhost", 8085)
    )
    .flatMap:
      _.subscribe(
        subscriptionName = SubscriptionName("any", "basic_example"),
        deserializer = Serde.int,
      ).mapZIO { (message, ackReply) =>
        for
          _ <- logInfo(
                 s"Received message" +
                   s" with id ${message.meta.messageId.value}" +
                   s" and data ${message.data}"
               )
          _ <- ackReply.ack()
        yield ()
      }.runDrain
