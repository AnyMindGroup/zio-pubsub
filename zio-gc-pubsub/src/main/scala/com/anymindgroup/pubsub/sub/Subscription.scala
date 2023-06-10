package com.anymindgroup.pubsub.sub

import zio.Duration

final case class Subscription(
  topicName: String,
  name: String,
  filter: Option[SubscriberFilter],
  enableOrdering: Boolean,
  expiration: Option[Duration],
)
