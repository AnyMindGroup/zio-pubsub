package com.anymindgroup.pubsub.sub

import zio.Duration

final case class Subscription(
  topicName: String,
  name: String,
  filter: Option[SubscriberFilter],
  enableOrdering: Boolean,
  expiration: Option[Duration],
  deadLettersSettings: Option[DeadLettersSettings], // Should be left None for subscription to dead letter topic
)

final case class DeadLettersSettings(
  deadLetterTopicName: String,
  maxRetryNum: Int,
)
