package com.anymindgroup.pubsub
package http

import zio.Scope
import zio.test.*

object HttpPubAndSubSpec extends ZIOSpecDefault {
  override def spec: Spec[Scope, Any] = PubAndSubSpec.spec(
    pkgName = "zio-pubsub-http",
    publisherImpl = (connection, topic) =>
      makeTopicPublisher(connection = connection, topicName = topic, serializer = Serde.utf8String),
    subscriberImpl = connection => makeSubscriber(connection = connection),
  )
}
