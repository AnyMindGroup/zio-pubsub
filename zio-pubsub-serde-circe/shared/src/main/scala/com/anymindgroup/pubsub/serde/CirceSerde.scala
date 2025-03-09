package com.anymindgroup.pubsub.serde

import com.anymindgroup.pubsub.{ReceivedMessage, Serde}
import io.circe.Codec
import io.circe.parser.decode

import zio.{Chunk, RIO, ZIO}

@deprecated("will be removed in future release in favor of zio-schema module")
object CirceSerde {
  def fromCirceCodec[T](codec: Codec[T]): Serde[Any, T] = new Serde[Any, T] {
    private implicit val c: Codec[T] = codec

    override def serialize(data: T): RIO[Any, Chunk[Byte]] =
      ZIO.succeed(Chunk.fromArray(codec(data).noSpacesSortKeys.getBytes))

    override def deserialize(message: ReceivedMessage.Raw): RIO[Any, T] =
      ZIO.fromEither(decode[T](String(message.data.toArray)))
  }
}
