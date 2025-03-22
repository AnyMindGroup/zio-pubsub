package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.Serde

import zio.Scope
import zio.test.*

object GooglePubAndSubSpec extends ZIOSpecDefault {
  override def spec: Spec[Scope, Any] =
    com.anymindgroup.pubsub.PubAndSubSpec.spec(
      pkgName = "zio-pubsub-google",
      publisherImpl = (connection, topic) =>
        makeTopicPublisher(
          topicName = topic,
          serializer = Serde.utf8String,
          connection = connection,
        ),
      subscriberImpl = connection => makeStreamingPullSubscriber(connection = connection),
    )
}
