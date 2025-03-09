package com.anymindgroup.pubsub

import zio.{NonEmptyChunk, RIO, Tag, ZIO}

trait Publisher[R, E] {
  def publish(message: PublishMessage[E]): RIO[R, MessageId]

  def publish(message: NonEmptyChunk[PublishMessage[E]]): RIO[R, NonEmptyChunk[MessageId]]
}

object Publisher {
  def publish[R, E](message: PublishMessage[E])(implicit t: Tag[Publisher[R, E]]): RIO[Publisher[R, E] & R, MessageId] =
    ZIO.serviceWithZIO[Publisher[R, E]](_.publish(message))
}
