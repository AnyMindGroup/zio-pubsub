---
id: index
title: "Getting Started with ZIO Google Cloud Pub/Sub"
sidebar_label: "Getting Started"
---

[Google Cloud Pub/Sub](https://cloud.google.com/pubsub) client providing stream-based, declarative, high-level API with [zio](https://zio.dev) and [zio-streams](https://zio.dev/reference/stream) to help to concentrate on the business logic.

## Modules

- `zio-pubsub` Core components/interfaces/models
- `zio-pubsub-google` Provides subscriber, publisher and admin clients implementations using the [Google Java](https://cloud.google.com/java/docs/reference/google-cloud-pubsub/latest/overview) library
- `zio-pubsub-serde-circe` Provides Json Serializer/Deserializer using the [circe](https://circe.github.io/circe) codec
- `zio-pubsub-serde-vulcan` Provides Avro schema Serializer/Deserializer using the [vulcan](https://fd4s.github.io/vulcan) codec

Alternative implementations and codecs may be added later.

## Getting Started

To get started with sbt, add the following line to your build.sbt file to use the implementation with the Google Java library:

```scala
libraryDependencies += "com.anymindgroup" %% "zio-pubsub-google" % "@VERSION@"
```

## Usage examples

Create a stream for existing subscription:

```scala
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
```

Publish random integer every 2 seconds

```scala
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
```

Setup topics and subscription using the admin client:

```scala
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig}
import com.anymindgroup.pubsub.*
import zio.*

object ExamplesAdminSetup extends ZIOAppDefault:
  def run: Task[Unit] = PubsubAdmin.setup(
    connection = PubsubConnectionConfig.Emulator(
      PubsubConnectionConfig.GcpProject("any"),
      "localhost:8085",
    ),
    topics = List(exampleTopic, exampleDeadLettersTopic),
    subscriptions = List(exampleSub, exampleDeadLettersSub),
  )

  val exampleTopic: Topic[Any, Int] = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  val exampleDeadLettersTopic: Topic[Any, Int] =
    exampleTopic.copy(name = s"${exampleTopic.name}__dead_letters")

  val exampleSub: Subscription = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
    deadLettersSettings = Some(DeadLettersSettings(exampleDeadLettersTopic.name, 5)),
  )

  val exampleDeadLettersSub: Subscription = exampleSub.copy(
    topicName = exampleDeadLettersTopic.name,
    name = s"${exampleSub.name}__dead_letters",
    deadLettersSettings = None,
  )
```

See [examples](https://github.com/AnyMindGroup/zio-pubsub/tree/master/examples/google) for more examples.

### Running example code

Start Google Pub/Sub emulator with docker-compose unsing provided docker-compose.yaml

```shell
docker-compose up
```

Run examples with sbt:

```shell
# run to setup example topics + subscriptions
sbt '+examplesGoogle/runMain ExamplesAdminSetup'

# start basic subscription
sbt '+examplesGoogle/runMain BasicSubscription'

# run samples publisher in a separate shell
sbt '+examplesGoogle/runMain SamplesPublisher'

# or choose in sbt which example to run
sbt '+examplesGoogle/run'
```
