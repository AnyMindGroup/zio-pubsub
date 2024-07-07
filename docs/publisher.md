# Publisher

Simple example for publishing one message with a random string:

```scala
import com.anymindgroup.pubsub.*, zio.*, zio.Console.*

object PublishRandomString extends ZIOAppDefault:
  // publish a random string
  def publish = for {
    data <- Random.nextString(10)
    message = PublishMessage(
                data = data,
                attributes = Map.empty,
                orderingKey = None,
              )
    messageId <- Publisher.publish(message)
    _         <- printLine(s"Published message with id $messageId")
  } yield ()

  // publisher implementation based on Google's Java library
  // - publishes to topic "basic_example"
  // - ordering disabled
  // - use UTF8 string encoder/serializer
  // - use binary encoding
  val publisherImpl: TaskLayer[Publisher[Any, String]] = {
    import com.anymindgroup.pubsub.google as G

    val publisherConfig = G.PublisherConfig(
      connection = G.PubsubConnectionConfig.Emulator(
        G.PubsubConnectionConfig.GcpProject("any"),
        "localhost:8085",
      ),
      topicName = "basic_example",
      encoding = Encoding.Binary,
      enableOrdering = false,
    )

    ZLayer.scoped(
      G.Publisher.make(
        config = publisherConfig,
        ser = Serde.utf8String,
      )
    )
  }

  // execute publishing
  def run = publish.provide(publisherImpl)
```