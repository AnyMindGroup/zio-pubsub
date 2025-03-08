package com.anymindgroup.pubsub.model

final case class SubscriptionName(projectId: String, subscription: String) {
  def fullName: String          = s"projects/$projectId/subscriptions/$subscription"
  override def toString: String = fullName
}
