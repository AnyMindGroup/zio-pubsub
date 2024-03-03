package com.anymindgroup.pubsub.serde

import zio.RIO

trait Serializer[-R, -T] {
  def serialize(data: T): RIO[R, Array[Byte]]
}
