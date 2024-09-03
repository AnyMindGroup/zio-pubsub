package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.sub.ReceivedMessage

import zio.RIO

trait Deserializer[-R, +T] {
  def deserialize(message: ReceivedMessage[Array[Byte]]): RIO[R, T]
}

trait Serializer[-R, -T] {
  def serialize(data: T): RIO[R, Array[Byte]]
}

trait Serde[-R, T] extends Serializer[R, T] with Deserializer[R, T] {}

object Serde extends Serdes {}
