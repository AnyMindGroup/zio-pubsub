package com.anymindgroup.pubsub.google

import scala.util.Try

import com.anymindgroup.pubsub.google.PubsubTestSupport.*
import com.anymindgroup.pubsub.model.{SchemaRegistry, SchemaType}
import com.google.pubsub.v1.{Schema, SchemaName}

import zio.test.Assertion.*
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, *}
import zio.{Scope, ZIO}

object PubsubAdminSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("PubsubAdminSpec")(
    test("crating a subscriptions for non existing topic fails") {
      for {
        connection   <- ZIO.service[PubsubConnectionConfig]
        subscription <- subscriptionsConfigsGen("no_topic").runHead.map(_.get)
        exit         <- PubsubAdmin.setup(connection, Nil, List(subscription)).exit
      } yield assert(exit)(fails(anything))
    },
    test("create topics and subscriptions if not exist") {
      for {
        connection    <- ZIO.service[PubsubConnectionConfig]
        topics        <- (topicConfigsGen <*> Gen.int(1, 100)).runCollectN(20)
        subscriptions <- ZIO.foreach(topics)(topic => subscriptionsConfigsGen(topic._1.name).runCollectN(1))
        _             <- PubsubAdmin.setup(connection, topics.map(_._1), subscriptions.flatten)
        _ <- ZIO.foreachDiscard(subscriptions.flatten) { subscription =>
               for {
                 maybePubsubSub <- findSubscription(subscription.name)
                 _              <- assert(maybePubsubSub)(isSome)
                 pubsubSub       = maybePubsubSub.get
                 pubsubSubName   = pubsubSub.getName().split("/").last
                 _              <- assert(pubsubSubName)(equalTo(subscription.name))
                 _              <- assert(pubsubSub.getEnableMessageOrdering())(equalTo(subscription.enableOrdering))
                 _              <- assert(pubsubSub.getFilter())(equalTo(subscription.filter.map(_.value).getOrElse("")))
                 _ <- assert(pubsubSub.getExpirationPolicy().getTtl().getSeconds())(
                        equalTo(subscription.expiration.map(_.getSeconds()).getOrElse(0L))
                      )
               } yield assertTrue(true)
             }
        exit <- PubsubAdmin.setup(connection, topics.map(_._1), subscriptions.flatten).exit
      } yield assert(exit)(succeeds(anything)).label("re-running same setup succeeds")
    },
    test("schema registry test") {
      for {
        connection <- ZIO.service[PubsubConnectionConfig]
        client     <- PubSubSchemaRegistryAdmin.makeClient(connection)
        _ <- PubSubSchemaRegistryAdmin.createIfNotExists(
               connection = connection,
               schemaRegistry = SchemaRegistry(
                 "topic_schema",
                 SchemaType.Avro,
                 ZIO.succeed(TestEvent.avroCodecSchema),
               ),
             )
        result = Try(client.getSchema(SchemaName.format(connection.project.name, "topic_schema"))).toEither
        _ <-
          assert(result.map(_.getName))(isRight(equalTo(s"projects/${connection.project.name}/schemas/topic_schema")))
        _ <- assert(result.map(_.getType))(isRight(equalTo(Schema.Type.AVRO)))
        _ <-
          assert(result.map(_.getDefinition))(
            isRight(
              equalTo(TestEvent.avroCodecSchema)
            )
          )
      } yield assertCompletes
    },
  ).provideSomeShared[Scope](
    emulatorConnectionConfigLayer(),
    SubscriptionAdmin.layer,
  ) @@ TestAspect.nondeterministic
}
