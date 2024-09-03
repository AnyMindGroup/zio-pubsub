package com.anymindgroup.pubsub.model

sealed abstract case class OrderingKey private (_value: String) {
  def value: String = _value
}

object OrderingKey {
  def fromString(o: String): Option[OrderingKey] = Option(o).filter(_.nonEmpty).map(new OrderingKey(_) {})
}
