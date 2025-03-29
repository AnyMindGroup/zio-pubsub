---
id: index
title: "Getting Started with ZIO Google Cloud Pub/Sub"
sidebar_label: "Getting Started"
---

![Maven Central Version](https://img.shields.io/maven-central/v/com.anymindgroup/zio-pubsub_3)

[Google Cloud Pub/Sub](https://cloud.google.com/pubsub) client providing stream-based, declarative, high-level API with [zio](https://zio.dev) and [zio-streams](https://zio.dev/reference/stream) to help to concentrate on the business logic.

Released for Scala 3 targeting JVM and Native via [scala-native](https://scala-native.org) with exception of some modules due to Java dependencies.   
[Scala.js](https://www.scala-js.org) support could be potentially added.  

_Scala 2.13 release will be kept in `v0.2.x` release series in the`series/0.2.x` branch, but not officially maintained. If you still rely on 2.13 release and require updates you may raise PRs targeting the `series/0.2.x` branch or create a fork_.  

## Modules

| Name | Description | JVM | Native |
| ---- | ----------- | --- | ------ |
| `zio-pubsub` | Core components/interfaces/models | ✅ | ✅ |
| `zio-pubsub-http` | Implementation using Pub/Sub REST API based on clients from [AnyMindGroup/zio-gcp](https://github.com/AnyMindGroup/zio-gcp) | ✅ | ✅ |
| `zio-pubsub-google` | Provides gRPC based client implementations via [Google's Java](https://cloud.google.com/java/docs/reference/google-cloud-pubsub/latest/overview) library by using the [StreamingPull API](https://cloud.google.com/pubsub/docs/pull#streamingpull_api) for subscriptions. | ✅ | ❌ |
| `zio-pubsub-serde-zio-schema` | Provides Serializer/Deserializer using the [zio-schema](https://github.com/zio/zio-schema) binary codec | ✅ | ✅ |

## Getting Started

To get started with sbt, add the following line to your build.sbt file:

```scala
libraryDependencies += "com.anymindgroup" %% "zio-pubsub-http" % "@VERSION@"
// or for Google's Java client based implementation:
// libraryDependencies += "com.anymindgroup" %% "zio-pubsub-google" % "@VERSION@"
// Both can also be used interchangeably
```

## Usage examples

#### Create a stream for existing subscription:

```scala
import com.anymindgroup.pubsub.*, zio.*, zio.stream.ZStream

object BasicSubscription extends ZIOAppDefault:
  def run =
    // create a subscription stream based on Subscriber implementation provided
    def subStream(s: Subscriber): ZStream[Any, Throwable, Unit] =
      s.subscribe(
        subscriptionName = SubscriptionName("gcp_project", "subscription"),
        deserializer = Serde.utf8String,
      ).mapZIO: (message, ackReply) =>
        for
          _ <- ZIO.logInfo(
                 s"Received message" +
                   s" with id ${message.messageId.value}" +
                   s" and data ${message.data}"
               )
          _ <- ackReply.ack()
        yield ()

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

    makeSubscriber.flatMap(subStream(_).runDrain)
```

#### Publish random string every 2 seconds

```scala
import com.anymindgroup.pubsub.*, zio.stream.*, zio.*, zio.ZIO.*

object SamplesPublisher extends ZIOAppDefault:
  def run =
    // run samples publishing given Publisher implementation
    def samplesPublish(p: Publisher[Any, String]) =
      ZStream
        .repeatZIOWithSchedule(Random.nextInt.map(i => s"some data $i"), Schedule.fixed(2.seconds))
        .mapZIO { sample =>
          for {
            mId <- p.publish(
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

    val makePublisher: RIO[Scope, Publisher[Any, String]] =
      // make http based topic publisher
      http.makeTopicPublisher(
        topicName = TopicName("gcp_project", "topic"),
        serializer = Serde.utf8String,
        // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
        connection = PubsubConnectionConfig.Emulator("localhost", 8085),
      )
      // or similarly by using gRCP based implementation via Google's Java client:
      // google.makeTopicPublisher(
      //   topicName = TopicName("gcp_project", "topic"),
      //   serializer = Serde.utf8String,
      //   connection = PubsubConnectionConfig.Emulator("localhost", 8085),
      // )

    makePublisher.flatMap(samplesPublish)
```

#### Setup topics and subscription with dead letter settings using the admin api:

```scala
import com.anymindgroup.gcp.pubsub.v1.*
import com.anymindgroup.pubsub.*, http.*
import zio.*

object ExamplesAdminSetup extends ZIOAppDefault:
  // topics
  val exampleTopic            = TopicName("gcp_project", "topic")
  val exampleDeadLettersTopic = exampleTopic.copy(topic = s"${exampleTopic.topic}__dead_letters")

  // subscriptions
  val subName = SubscriptionName(exampleTopic.projectId, "subscription")
  val exampleSub: Subscription = Subscription(
    topicName = exampleTopic,
    name = subName,
    filter = None,
    enableOrdering = false,
    expiration = None,
    deadLettersSettings = Some(DeadLettersSettings(exampleDeadLettersTopic, 5)),
  )
  val exampleDeadLettersSub: Subscription = exampleSub.copy(
    topicName = exampleDeadLettersTopic,
    name = subName.copy(subscription = s"${subName.subscription}__dead_letters"),
    deadLettersSettings = None,
  )

  def run =
    makeAuthedBackend(
      // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
      connection = PubsubConnectionConfig.Emulator(host = "localhost", port = 8085)
    ).flatMap: backend =>
      for
        _ <- ZIO.foreach(List(exampleTopic, exampleDeadLettersTopic)): topic =>
               resources.projects.Topics
                 .create(
                   projectsId = topic.projectId,
                   topicsId = topic.topic,
                   request = schemas.Topic(name = topic.fullName),
                 )
                 .send(backend)
        _ <- ZIO.foreach(List(exampleSub, exampleDeadLettersSub)): subcription =>
               resources.projects.Subscriptions
                 .create(
                   projectsId = subcription.name.projectId,
                   subscriptionsId = subcription.name.subscription,
                   request = schemas.Subscription(
                     name = subcription.name.fullName,
                     topic = subcription.topicName.fullName,
                   ),
                 )
                 .send(backend)
      yield ()
```

To run the example start Google Pub/Sub emulator with docker-compose unsing provided docker-compose.yaml

```shell
docker-compose up
```

Run examples with sbt:

```shell
# run to setup example topics + subscription
sbt 'examples/runMain ExamplesAdminSetup'

# run subscription
sbt 'examples/runMain BasicSubscription'

# run samples publisher
sbt 'examples/runMain SamplesPublisher'

# or choose in sbt which example to run
sbt 'examples/run'
```

For more examples check out the `examples` folder or the files 
[Http PubsubCloudTest.scala](https://github.com/AnyMindGroup/zio-pubsub/blob/master/zio-pubsub-test/shared/src/test/scala/com/anymindgroup/pubsub/PubsubCloudTest.scala) or
[Google PubsubCloudTest.scala](https://github.com/AnyMindGroup/zio-pubsub/blob/master/zio-pubsub-google/src/test/scala/com/anymindgroup/pubsub/google/PubsubCloudTest.scala)
which contain usage of subscriber, publisher and the admin api running against an actaul Pub/Sub in the cloud for manual execution.  
