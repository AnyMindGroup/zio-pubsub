package com.anymindgroup.pubsub
package http

import com.anymindgroup.gcp.auth.{AuthedBackend, TokenProvider}
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode

import zio.test.*
import zio.{Ref, Schedule, Scope, Task, ZIO}

object HttpPubsubIntegrationSpec extends ZIOSpecDefault {
  override def spec: Spec[Scope, Any] =
    suite("HttpPubAndSubSpec")(
      PubsubIntegrationSpec.spec(
        pkgName = "zio-pubsub-http",
        publisherImpl = (connection, topic) =>
          makeTopicPublisher(connection = connection, topicName = topic, serializer = Serde.utf8String),
        subscriberImpl = connection => makeSubscriber(connection = connection),
      ),
      suite("HttpSubscriberSpec")(
        List(true, false).map: ack =>
          test(s"failed ${if ack then "ack" else "nack"} requests are retried by provided schedule") {
            ZIO.scoped:
              for {
                ackCounter <- Ref.make(0)
                backend    <- backendStub(ackCounter)
                maxRetries  = 5
                subscriber <- makeSubscriber(
                                backend = Some(backend),
                                retrySchedule = Schedule.recurs(maxRetries),
                              )
                exit <- subscriber
                          .subscribe(SubscriptionName("any", "any"), Serde.utf8String)
                          .mapZIO((_, reply) => if ack then reply.ack() else reply.nack())
                          .runDrain
                          .exit
                _ <- assertTrue(exit.isFailure)
                _ <- ackCounter.get.map(retries => assertTrue(retries == maxRetries))
              } yield assertCompletes
          }
      ),
    ) @@ TestAspect.native(TestAspect.parallelN(2))

  private def backendStub(ackCounter: Ref[Int]) =
    Ref
      .make(0)
      .map: pullCount =>
        AuthedBackend(
          TokenProvider.noTokenProvider,
          BackendStub[Task](new RIOMonadAsyncError[Any])
            .whenRequestMatches(r => r.uri.pathToString.endsWith(":pull"))
            .thenRespondF {
              // return a message on first pull only
              pullCount.get.flatMap:
                case 0 =>
                  pullCount.getAndIncrement.as(
                    ResponseStub.adjust(
                      """|{
                         |  "receivedMessages":[
                         |    {
                         |      "ackId": "1",
                         |      "message": {
                         |        "data": "",
                         |        "messageId": "1",
                         |        "publishTime": "2025-01-01T00:00:00Z"
                         |      }
                         |    }
                         |  ]
                         |}""".stripMargin,
                      StatusCode.Ok,
                    )
                  )
                case _ => ZIO.never
            }
            .whenRequestMatches(r => r.uri.pathToString.endsWith(":acknowledge"))
            // return failure on any acknowledge request
            .thenRespondF(ackCounter.getAndIncrement.as(ResponseStub.adjust("", StatusCode(400))))
            .whenAnyRequest
            .thenRespondNotFound(),
        )
}
