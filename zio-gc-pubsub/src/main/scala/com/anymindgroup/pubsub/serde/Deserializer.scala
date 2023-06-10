package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.sub.ReceivedMessage

import zio.RIO

trait Deserializer[-R, +T] {
  def deserialize(message: ReceivedMessage.Raw): RIO[R, T]
}
