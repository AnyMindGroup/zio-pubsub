package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.TokenProvider
import com.anymindgroup.http.httpBackendScoped
import com.anymindgroup.pubsub.*

import zio.Scope
import zio.test.*

object HttpPubAndSubSpec extends ZIOSpecDefault {
  override def spec: Spec[Scope, Any] = PubAndSubSpec.spec(
    pkgName = "zio-pubsub-http",
    createPublisher = (connection, topic, encoding, enableOrdering) =>
      httpBackendScoped().map: backend =>
        HttpPublisher.make(
          connection = connection,
          topicName = topic,
          serializer = Serde.utf8String,
          backend = backend,
          tokenProvider = TokenProvider.noTokenProvider,
        ),
    createSubscriber = connection =>
      httpBackendScoped().flatMap: backend =>
        HttpSubscriber.make(
          connection = connection,
          backend = backend,
          tokenProvider = TokenProvider.noTokenProvider,
        ),
  )
}
