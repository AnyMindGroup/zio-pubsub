package com.anymindgroup.pubsub.google

import zio.Scope
import zio.test.*

object PublisherSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] = suite("PublisherSpec")(
    test("publish messages") {
      assertTrue(false).label("TODO implement")
    }
  )
}
