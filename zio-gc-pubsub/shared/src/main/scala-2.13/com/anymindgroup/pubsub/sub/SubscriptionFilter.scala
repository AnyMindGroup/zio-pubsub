package com.anymindgroup.pubsub.sub

sealed abstract case class SubscriberFilter private (_value: String) {
  def value: String = _value
}

object SubscriberFilter {
  // TODO extend accoding to specs: https://cloud.google.com/pubsub/docs/subscription-message-filter#filtering_syntax
  def matchingAttributes(values: Map[String, String]): SubscriberFilter =
    new SubscriberFilter(values.toList.map { case (k, v) => s"""attributes.$k="$v"""" }.mkString("", " AND ", "")) {}

  def of(builtString: String): SubscriberFilter = new SubscriberFilter(builtString) {}

}
