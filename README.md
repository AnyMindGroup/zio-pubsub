# ZIO Google Cloud Pub/Sub

[Google Cloud Pub/Sub](https://cloud.google.com/pubsub) client providing stream-based, purely functional API with [ZIO](https://zio.dev) and [ZIO Streams](https://zio.dev/reference/stream).

# Modules
 - `zio-gc-pubsub` Provides shared components/interfaces/models
 - `zio-gc-pubsub-google` Provides subscriber, publisher and admin clients implementations using the [Google Java](https://cloud.google.com/java/docs/reference/google-cloud-pubsub/latest/overview) library
 - `zio-gc-pubsub-serde-circe` Provides Json Serializer/Deserializer using the [circe](https://circe.github.io/circe) codec
 - `zio-gc-pubsub-serde-vulcan` Provides Avro schema Serializer/Deserializer using the [vulcan](https://fd4s.github.io/vulcan) codec

Alternative implementations and codecs may be added later.

## Getting Started

To get started with sbt, add the following line to your build.sbt file to use the implementation with the Google Java library:
```scala
libraryDependencies += "com.anymindgroup" %% "zio-gc-pubsub-google" % zioGcPubsubVersion
```

## Example

Example code that setups a sample topic + subscription and creates a subscription stream with a publisher producing random values in the background (see [examples/google/src/main/scala/MyPubSubApp.scala](examples/google/src/main/scala/MyPubSubApp.scala))
```scala
package examples.google

import com.anymindgroup.pubsub.google
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig}
import com.anymindgroup.pubsub.model.{Encoding, SchemaSettings, Topic}
import com.anymindgroup.pubsub.pub.{PublishMessage, Publisher}
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.{Subscriber, Subscription}

import zio.Console.printLine
import zio.stream.ZStream
import zio.{Random, Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer, durationInt}

object MyPubSubApp extends ZIOAppDefault {
  type Env = Scope & ZIOAppArgs

  override def run: ZIO[Env, Any, Any] = (for {
    consumeAmount <- ZIOAppArgs.getArgs.map(_.headOption.flatMap(_.toLongOption).getOrElse(10L))
    _             <- subStream(consumeAmount).drainFork(pubStream).runDrain
    _             <- printLine(s"done consuming $consumeAmount")
  } yield ()).provideSome[Env](examplesSetup, publisherLayer, subscriberLayer)

  def subStream(consumeAmount: Long): ZStream[Subscriber, Throwable, Unit] =
    Subscriber
      .subscribe(exampleSub.name, Serde.int)
      .zipWithIndex
      .mapZIO { case ((message, ackReply), idx) =>
        for {
          _ <- printLine(
                 s"Received message ${idx + 1} / ${consumeAmount}"
                   + s" with id ${message.meta.messageId.value}"
                   + s" and data ${message.data}"
               )
          _ <- ackReply.ack()
        } yield ()
      }
      .take(consumeAmount)

  val pubStream: ZStream[Publisher[Any, Int], Throwable, Unit] = ZStream
    .repeatZIOWithSchedule(
      Random.nextIntBetween(0, Int.MaxValue),
      Schedule.fixed(2.seconds),
    )
    .mapZIO { sampleData =>
      Publisher.publish[Any, Int](
        PublishMessage(
          data = sampleData,
          attributes = Map.empty,
          orderingKey = None,
        )
      )
    }
    .drain

  val pubsubConnection: PubsubConnectionConfig =
    PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085")

  val exampleTopic: Topic[Any, Int] = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  val exampleSub: Subscription = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
  )

  val examplesSetup: ZLayer[Any, Throwable, Unit] = ZLayer.fromZIO(
    PubsubAdmin.setup(pubsubConnection, List(exampleTopic), List(exampleSub))
  )

  val publisherLayer: ZLayer[Scope, Throwable, Publisher[Any, Int]] = ZLayer.fromZIO(
    google.Publisher.make(
      connection = pubsubConnection,
      topic = exampleTopic,
      enableOrdering = false,
    )
  )

  val subscriberLayer: ZLayer[Scope, Throwable, Subscriber] = ZLayer.fromZIO(
    google.Subscriber.makeStreamingPullSubscriber(pubsubConnection)
  )
}
```

### Running example code 
Start Google Pub/Sub emulator with docker:
```shell
 docker run -p 8085:8085 --rm gcr.io/google.com/cloudsdktool/cloud-sdk:427.0.0-emulators -- gcloud beta emulators pubsub start --project=any --host-port=0.0.0.0:8085
```
or with docker-compose unsing provided docker-compose.yaml
```shell
docker-compose up
```

Run example with sbt:
```shell
sbt 'examplesGoogle/run 5' # will terminate after the subscriber received 5 messages
```