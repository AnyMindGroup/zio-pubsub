import com.anymindgroup.gcp.pubsub.v1.*
import com.anymindgroup.pubsub.*, http.*

import zio.*

object ExamplesAdminSetup extends ZIOAppDefault:
  // topics
  val exampleTopic            = TopicName(projectId = "any", topic = "basic_example")
  val exampleDeadLettersTopic = exampleTopic.copy(topic = s"${exampleTopic.topic}__dead_letters")

  // subscriptions
  val subName = SubscriptionName(projectId = exampleTopic.projectId, subscription = "basic_example")
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
    defaultBackendByConfig(
      PubsubConnectionConfig.Emulator(host = "localhost", port = 8085)
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
