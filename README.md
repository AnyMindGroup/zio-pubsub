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

## Usage examples

Create a stream for existing subscription (see [examples/google/src/main/scala/BasicSubscription.scala](examples/google/src/main/scala/BasicSubscription.scala))
```scala
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
```

Publish random integer every 2 seconds (see [examples/google/src/main/scala/SamplesPublisher.scala](examples/google/src/main/scala/SamplesPublisher.scala))
```scala
import com.anymindgroup.pubsub.google.{Publisher as GooglePublisher, PublisherConfig, PubsubConnectionConfig}
import com.anymindgroup.pubsub.model.Encoding
import com.anymindgroup.pubsub.pub.{PublishMessage, Publisher}
import com.anymindgroup.pubsub.serde.Serde

import zio.stream.ZStream
import zio.{Console, Random, Schedule, Scope, ZIO, ZIOAppDefault, ZLayer, durationInt}

object SamplesPublisher extends ZIOAppDefault {
  override def run: ZIO[Scope, Any, Any] = ZStream
    .repeatZIOWithSchedule(
      Random.nextIntBetween(0, Int.MaxValue),
      Schedule.fixed(2.seconds),
    )
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
    .provideSome[Scope](publisher)

  private val publisher = ZLayer.fromZIO(
    GooglePublisher.make(
      config = PublisherConfig(
        connection = PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085"),
        topicName = "basic_example",
        encoding = Encoding.Binary,
        enableOrdering = false,
      ),
      ser = Serde.int,
    )
  )
}
```

Setup topics and subscription using the admin client (see [examples/google/src/main/scala/ExamplesAdminSetup.scala](examples/google/src/main/scala/ExamplesAdminSetup.scala)):
```scala
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig}
import com.anymindgroup.pubsub.model.{Encoding, SchemaSettings, Topic}
import com.anymindgroup.pubsub.serde.Serde
import com.anymindgroup.pubsub.sub.Subscription

import zio.{Scope, ZIO, ZIOAppDefault}

object ExamplesAdminSetup extends ZIOAppDefault {
  override def run: ZIO[Scope, Any, Any] = PubsubAdmin.setup(
    connection = PubsubConnectionConfig.Emulator(PubsubConnectionConfig.GcpProject("any"), "localhost:8085"),
    topics = List(exampleTopic),
    subscriptions = List(exampleSub),
  )

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
}
```

See [examples](examples/google) for more examples.

### Running example code 
Start Google Pub/Sub emulator with docker:
```shell
 docker run -p 8085:8085 --rm gcr.io/google.com/cloudsdktool/cloud-sdk:427.0.0-emulators -- gcloud beta emulators pubsub start --project=any --host-port=0.0.0.0:8085
```
or with docker-compose unsing provided docker-compose.yaml
```shell
docker-compose up
```

Run examples with sbt:
```shell
# run to setup example topics + subscriptions
sbt 'examplesGoogle/runMain ExamplesAdminSetup'

# start basic subscription
sbt 'examplesGoogle/runMain BasicSubscription'

# run samples publisher in a separate shell
sbt 'examplesGoogle/runMain SamplesPublisher'
```