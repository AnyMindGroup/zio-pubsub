package com.anymindgroup.pubsub.model

enum PubsubConnectionConfig:
  case Cloud
  case Emulator(host: String, port: Int = 8085)

opaque type GcpProject = String
object GcpProject:
  def apply(name: String): GcpProject        = name
  extension (a: GcpProject) def name: String = a
