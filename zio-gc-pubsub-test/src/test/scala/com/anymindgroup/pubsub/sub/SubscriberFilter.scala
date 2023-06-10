package com.anymindgroup.pubsub.sub

import zio.Scope
import zio.test.Assertion.equalTo
import zio.test.*

object SubscriberFilterSpec extends ZIOSpecDefault {

  // https://cloud.google.com/pubsub/docs/subscription-message-filter#filtering_syntax
  override def spec: Spec[TestEnvironment & Scope, Any] = suite("SubscriberFilterSpec")(
    test("create from map") {
      import SubscriberFilter.*

      assert(matchingAttributes(Map.empty).value)(equalTo(""))
        && assert(matchingAttributes(Map("a" -> "b")).value)(equalTo("""attributes.a="b""""))
        && assert(matchingAttributes(Map("a" -> "b", "c" -> "d")).value)(
          equalTo("""attributes.a="b" AND attributes.c="d"""")
        )
    }
  )

}
