package com.anymindgroup.pubsub.model

final case class TopicName(projectId: String, topic: String) {
  def path: String              = s"projects/$projectId/topics/$topic"
  override def toString: String = path
}
