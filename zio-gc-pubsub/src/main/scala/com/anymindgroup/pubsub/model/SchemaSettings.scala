package com.anymindgroup.pubsub.model

import zio.Task

sealed trait SchemaType

object SchemaType {
  case object ProtocolBuffer extends SchemaType
  case object Avro           extends SchemaType
}

final case class SchemaRegistry(
  id: String,
  schemaType: SchemaType,
  definition: Task[String],
)

final case class SchemaSettings(
  encoding: Encoding,
  schema: Option[SchemaRegistry],
)

sealed trait Encoding
object Encoding {
  case object Binary extends Encoding
  case object Json   extends Encoding
}
