package com.anymindgroup.pubsub.pub

import com.anymindgroup.pubsub.model.MessageId

import zio.RIO

trait Publisher[R, E] {
  def publishEvent(event: PublishMessage[E]): RIO[R, MessageId]
}
