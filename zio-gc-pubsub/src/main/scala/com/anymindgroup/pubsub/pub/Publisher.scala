package com.anymindgroup.pubsub.pub

import com.anymindgroup.pubsub.model.MessageId

import zio.{RIO, Tag, ZIO}

trait Publisher[R, E] {
  def publish(message: PublishMessage[E]): RIO[R, MessageId]
}

object Publisher {
  def publish[R, E](message: PublishMessage[E])(implicit t: Tag[Publisher[R, E]]): RIO[Publisher[R, E] & R, MessageId] =
    ZIO.serviceWithZIO[Publisher[R, E]](_.publish(message))
}
