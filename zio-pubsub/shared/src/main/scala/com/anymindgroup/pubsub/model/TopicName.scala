package com.anymindgroup.pubsub.model

final case class TopicName(projectId: String, topic: String) {
  def fullName: String          = s"projects/$projectId/topics/$topic"
  override def toString: String = fullName
}

object TopicName:
  def parse(input: String) = input.split('/') match
    case Array("projects", projectId, "topics", topic) => Right(TopicName(projectId = projectId, topic = topic))
    case _                                             => Left(s"Invalid topic name: $input")
