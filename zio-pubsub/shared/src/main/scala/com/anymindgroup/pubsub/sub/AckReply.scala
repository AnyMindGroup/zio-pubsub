package com.anymindgroup.pubsub.sub

import zio.UIO

final case class AckId(value: String) extends AnyVal

trait AckReply {
  def ack(): UIO[Unit]
  def nack(): UIO[Unit]
}
