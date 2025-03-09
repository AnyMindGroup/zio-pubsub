package com.anymindgroup.pubsub.google

import vulcan.Codec
import vulcan.generic.*

import zio.test.*

object TestSupport {
  final case class TestEvent(name: String, age: Int)
  object TestEvent {
    implicit val avroCodec: Codec[TestEvent]          = Codec.derive[TestEvent]
    val avroCodecSchema: String                       = TestEvent.avroCodec.schema.map(_.toString()).toOption.get
    implicit val jsonCodec: io.circe.Codec[TestEvent] = io.circe.generic.semiauto.deriveCodec[TestEvent]
  }

  val testEventGen: Gen[Any, TestEvent] = for {
    name <- Gen.alphaNumericString
    age  <- Gen.int
  } yield TestEvent(name, age)
}
