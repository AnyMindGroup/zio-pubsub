package com.anymindgroup.pubsub.http
import zio.json.*
import com.anymindgroup.pubsub.model.MessageId

final case class MessageIds(messageIds: List[MessageId])
object MessageIds {
  implicit val messageIdDecoder: JsonDecoder[MessageId] = zio.json.JsonDecoder.string.map(s => MessageId(s))
  implicit val decoder: JsonDecoder[MessageIds]         = zio.json.DeriveJsonDecoder.gen[MessageIds]
}
