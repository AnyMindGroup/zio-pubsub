import com.anymindgroup.pubsub.google.{PubsubConnectionConfig, Subscriber as GoogleSubscriber}
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.Subscriber

import zio.{Console, Scope, ZIO, ZIOAppDefault, ZLayer}

object BasicSubscription extends ZIOAppDefault {
  override def run: ZIO[Scope, Any, Any] =
    Subscriber
      .subscribe(subscriptionName = "basic_example", des = Serde.int)
      .zipWithIndex
      .mapZIO { case ((message, ackReply), idx) =>
        for {
          _ <- Console.printLine(
                 s"Received message ${idx + 1}"
                   + s" with id ${message.meta.messageId.value}"
                   + s" and data ${message.data}"
               )
          _ <- ackReply.ack()
        } yield ()
      }
      .runDrain
      .provideSome[Scope](subscriber)

  private val subscriber = ZLayer.fromZIO(
    GoogleSubscriber.makeStreamingPullSubscriber(
      connection = PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085")
    )
  )
}
