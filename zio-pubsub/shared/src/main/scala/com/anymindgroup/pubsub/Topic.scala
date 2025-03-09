package com.anymindgroup.pubsub

final case class Topic[R, T](name: TopicName, schemaSetting: SchemaSettings, serde: Serde[R, T])
