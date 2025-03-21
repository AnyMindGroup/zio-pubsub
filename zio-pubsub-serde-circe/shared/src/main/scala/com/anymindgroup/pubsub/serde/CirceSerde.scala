package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.sub.ReceivedMessage
import io.circe.Codec
import io.circe.parser.decode

import zio.{RIO, ZIO}

@deprecated("Will be removed from release 0.3 in favor of zio-schema.", since = "0.2.11")
object CirceSerde {
  def fromCirceCodec[T](codec: Codec[T]): Serde[Any, T] = new Serde[Any, T] {
    private implicit val c: Codec[T] = codec

    override def serialize(data: T): RIO[Any, Array[Byte]] = ZIO.succeed(codec(data).noSpacesSortKeys.getBytes)

    override def deserialize(message: ReceivedMessage.Raw): RIO[Any, T] =
      ZIO.fromEither(decode[T](new String(message.data)))
  }
}
