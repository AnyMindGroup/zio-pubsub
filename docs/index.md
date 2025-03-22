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
| `zio-pubsub-http` | Implementation using Pub/Sub REST API based on clients from [zio-gcp](https://github.com/AnyMindGroup/zio-gcp) | ✅ | ✅ |
| `zio-pubsub-google` | Provides [StreamingPull API](https://cloud.google.com/pubsub/docs/pull#streamingpull_api) based subscriber client and publisher implementations using [Google's Java](https://cloud.google.com/java/docs/reference/google-cloud-pubsub/latest/overview) library | ✅ | ❌ |
| `zio-pubsub-serde-zio-schema` | Provides Serializer/Deserializer using the [zio-schema](https://github.com/zio/zio-schema) binary codec | ✅ | ✅ |

## Getting Started

To get started with sbt, add the following line to your build.sbt file to use the http implementation:

```scala
libraryDependencies += "com.anymindgroup" %% "zio-pubsub-http" % "@VERSION@"
```

## Usage examples

Create a stream for existing subscription:

```scala
import com.anymindgroup.pubsub.*, http.*
import zio.*, zio.ZIO.*

object BasicSubscription extends ZIOAppDefault:
  def run = makeSubscriber(
    // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
    connection = PubsubConnectionConfig.Emulator("localhost", 8085)
  ).flatMap:
    _.subscribe(
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
```

Publish random string every 2 seconds

```scala
import com.anymindgroup.pubsub.*, http.*
import zio.stream.*, zio.*, zio.ZIO.*

object SamplesPublisher extends ZIOAppDefault:
  def run = makeTopicPublisher(
    topicName = TopicName("gcp_project", "topic"),
    serializer = Serde.utf8String,
    // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
    connection = PubsubConnectionConfig.Emulator("localhost", 8085),
  ).flatMap: publisher =>
    ZStream
      .repeatZIOWithSchedule(Random.nextInt.map(i => s"some data $i"), Schedule.fixed(2.seconds))
      .mapZIO { sample =>
        for {
          mId <- publisher.publish(
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
```

Setup topics and subscription with dead letter settings using the admin api:

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
