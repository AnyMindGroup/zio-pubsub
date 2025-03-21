package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.Serde

import zio.Scope
import zio.test.*

object GooglePubAndSubSpec extends ZIOSpecDefault {
  override def spec: Spec[Scope, Any] =
    com.anymindgroup.pubsub.PubAndSubSpec.spec(
      pkgName = "zio-pubsub-google",
      createPublisher = (connection, topic, encoding, enableOrdering) =>
        Publisher.make(
          topicName = topic,
          encoding = encoding,
          serialzer = Serde.utf8String,
          enableOrdering = enableOrdering,
          connection = connection,
        ),
      createSubscriber = connection => Subscriber.makeStreamingPullSubscriber(connection),
    )
}
