package com.anymindgroup.pubsub.serde

trait Serde[-R, T] extends Serializer[R, T] with Deserializer[R, T] {}

object Serde extends Serdes {}
