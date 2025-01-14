package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.model.*
import com.google.pubsub.v1.TopicName

final case class PublisherConfig(
    connection: PubsubConnectionConfig,
    topicName: String,
    encoding: Encoding,
    enableOrdering: Boolean,
) {
  val topicId: TopicName = TopicName.of(connection.project.name, topicName)
}

object PublisherConfig {
  def forTopic(connection: PubsubConnectionConfig, topic: Topic[?, ?], enableOrdering: Boolean): PublisherConfig =
    PublisherConfig(
      connection = connection,
      topicName = topic.name,
      encoding = topic.schemaSetting.encoding,
      enableOrdering = enableOrdering,
    )
}
