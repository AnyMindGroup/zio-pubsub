package com.anymindgroup.pubsub

import zio.Task

enum SchemaType:
  case ProtocolBuffer, Avro

final case class SchemaName(projectId: String, schemaId: String)

final case class SchemaRegistry(
  name: SchemaName,
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
