package com.anymindgroup.pubsub.model

opaque type MessageId = String
object MessageId:
  def apply(value: String): MessageId        = value
  extension (a: MessageId) def value: String = a
