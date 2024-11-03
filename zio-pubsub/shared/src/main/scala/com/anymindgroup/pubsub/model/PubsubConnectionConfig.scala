package com.anymindgroup.pubsub.model

sealed trait PubsubConnectionConfig {
  def project: PubsubConnectionConfig.GcpProject
}

object PubsubConnectionConfig {
  final case class Cloud(project: GcpProject)                                    extends PubsubConnectionConfig
  final case class Emulator(project: GcpProject, host: String, port: Int = 8085) extends PubsubConnectionConfig

  final case class GcpProject(name: String) extends AnyVal {
    override def toString: String = name
  }
}
