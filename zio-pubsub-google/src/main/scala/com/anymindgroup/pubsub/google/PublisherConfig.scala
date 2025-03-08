package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.model.*
import com.google.pubsub.v1.TopicName as GTopicName

final case class PublisherConfig(
  topicName: TopicName,
  encoding: Encoding,
  enableOrdering: Boolean,
):
  def topicId: GTopicName = GTopicName.of(topicName.projectId, topicName.topic)
