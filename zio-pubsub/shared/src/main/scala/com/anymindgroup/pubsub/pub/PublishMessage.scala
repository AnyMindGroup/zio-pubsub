package com.anymindgroup.pubsub.pub

import com.anymindgroup.pubsub.model.OrderingKey

final case class PublishMessage[E](data: E, orderingKey: Option[OrderingKey], attributes: Map[String, String])
