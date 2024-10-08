package com.anymindgroup.pubsub.model

import com.anymindgroup.pubsub.serde.Serde

final case class Topic[R, T](name: String, schemaSetting: SchemaSettings, serde: Serde[R, T])
