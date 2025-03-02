[//]: # (This file was autogenerated using `zio-sbt-website` plugin via `sbt generateReadme` command.)
[//]: # (So please do not edit it manually. Instead, change "docs/index.md" file or sbt setting keys)
[//]: # (e.g. "readmeDocumentation" and "readmeSupport".)

# ZIO Google Cloud Pub/Sub

![Maven Central Version](https://img.shields.io/maven-central/v/com.anymindgroup/zio-pubsub_3)

[Google Cloud Pub/Sub](https://cloud.google.com/pubsub) client providing stream-based, declarative, high-level API with [zio](https://zio.dev) and [zio-streams](https://zio.dev/reference/stream) to help to concentrate on the business logic.

Released for Scala 3 targeting JVM and Native via [scala-native](https://scala-native.org) with exception of some modules due to Java dependencies.   
[Scala.js](https://www.scala-js.org) support could be potentially added.  
_Scala 2.13 support will be frozed and live in the `series/0.2.x` branch_.  

## Modules

| Name | Description | JVM | Native |
| ---- | ----------- | --- | ------ |
| `zio-pubsub` | Core components/interfaces/models | ✅ | ✅ |
| `zio-pubsub-google` | Provides publisher, admin and [StreamingPull API](https://cloud.google.com/pubsub/docs/pull#streamingpull_api) based subscriber client implementations using [Google's Java](https://cloud.google.com/java/docs/reference/google-cloud-pubsub/latest/overview) library | ✅ | ❌ |
| `zio-pubsub-serde-zio-schema` | Provides Serializer/Deserializer using the [zio-schema](https://github.com/zio/zio-schema) binary codec | ✅ | ✅ |
| `zio-pubsub-serde-circe` | Provides Json Serializer/Deserializer using the [circe](https://circe.github.io/circe) codec | ✅ | ✅ |
| `zio-pubsub-serde-vulcan` | Provides Avro schema Serializer/Deserializer using the [vulcan](https://fd4s.github.io/vulcan) codec | ✅ | ❌ |


Alternative implementations and codecs may be added later.

## Getting Started

To get started with sbt, add the following line to your build.sbt file to use the implementation with the Google Java library:

```scala
libraryDependencies += "com.anymindgroup" %% "zio-pubsub-google" % "0.2.9"
```

## Usage examples

Create a stream for existing subscription:

```scala
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
        connection = G.PubsubConnectionConfig.Emulator(
          G.PubsubConnectionConfig.GcpProject("any"),
          "localhost:8085",
        )
      )
    )
  }
```

Publish random integer every 2 seconds

```scala
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
  val intPublisher: TaskLayer[Publisher[Any, Int]] = {
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

To run the example start Google Pub/Sub emulator with docker-compose unsing provided docker-compose.yaml

```shell
docker-compose up
```

Run examples with sbt:

```shell
# run to setup example topics + subscription
sbt '+examples/runMain ExamplesAdminSetup'

# run subscription
sbt '+examples/runMain BasicSubscription'

# run samples publisher
sbt '+examples/runMain SamplesPublisher'

# or choose in sbt which example to run
sbt '+examples/run'
```

## Documentation

Learn more on the [ZIO Google Cloud Pub/Sub homepage](https://anymindgroup.github.io/zio-pubsub)!

## Contributing

If you have any question or problem feel free to open an issue or discussion.

People are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md) when discussing on the GitHub issues or PRs.

## Code of Conduct

See the [Code of Conduct](CODE_OF_CONDUCT.md)

## Support

Open an issue or discussion on [GitHub](https://github.com/AnyMindGroup/zio-pubsub/issues)

## Credits

Inspired by libraries like [zio-kafka](https://github.com/zio/zio-kafka) 
and [fs2-pubsub](https://github.com/permutive-engineering/fs2-pubsub) to provide a similar experience.

## License

[License](LICENSE)
