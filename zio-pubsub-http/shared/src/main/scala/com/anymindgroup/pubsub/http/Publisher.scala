package com.anymindgroup.pubsub.http

import com.anymindgroup.pubsub.*
import zio.{ZIO, Task}
import java.util.Base64
import com.anymindgroup.gcp.auth.TokenProvider
import sttp.model.*
import sttp.client4.*
import zio.json.*
import com.anymindgroup.pubsub.model.MessageId
import zio.json.ast.Json
import zio.Chunk
import com.anymindgroup.pubsub.model.PubsubConnectionConfig
import com.anymindgroup.pubsub.model.PubsubConnectionConfig.Cloud
import com.anymindgroup.pubsub.model.PubsubConnectionConfig.Emulator

class HttpPublisher[R, E] private[http] (
  serde: Serializer[R, E],
  tokenProvider: Option[TokenProvider],
  baseRequest: Request[Either[String, MessageId]],
  httpBackend: GenericBackend[Task, Any],
) extends Publisher[R, E] {

  override def publish(event: PublishMessage[E]): ZIO[R, Throwable, MessageId] =
    for {
      request  <- buildRequest(event)
      response <- httpBackend.send(request)
      id       <- ZIO.fromEither(response.body).mapError(e => new Throwable(e))
    } yield id

  private def buildRequest(event: PublishMessage[E]) =
    for {
      body <- toRequestBody(event)
      req <- tokenProvider match {
               case Some(tp) =>
                 tp.accessToken.map { t =>
                   baseRequest.body(body.toString).auth.bearer(t.token.token.value.mkString)
                 }
               case _ => ZIO.succeed(baseRequest.body(body.toString))
             }
    } yield req

  private def toRequestBody(event: PublishMessage[E]) = for {
    msgBody <- serde.serialize(event.data).map(Base64.getEncoder.encodeToString)
    attrs    = Chunk.fromIterable(event.attributes.map { case (k, v) => (k, Json.Str(v)) })
    orderingKey = event.orderingKey match {
                    case Some(k) => Chunk("orderingKey" -> Json.Str(k.value))
                    case _       => Chunk.empty
                  }
    message = orderingKey ++ Chunk(
                "data"       -> Json.Str(msgBody),
                "attributes" -> Json.Obj(attrs),
              )
  } yield Json.Obj(
    "messages" -> Json.Arr(Json.Obj(message))
  )
}

object HttpPublisher {
  def make[R, E](
    connection: PubsubConnectionConfig,
    topic: String,
    serde: Serializer[R, E],
    httpBackend: GenericBackend[Task, Any],
    tokenProvider: Option[TokenProvider],
  ): Either[String, HttpPublisher[R, E]] =
    toPublishUri(connection, topic).map(toBasePublishRequest(_)).map { baseReq =>
      new HttpPublisher[R, E](
        serde = serde,
        tokenProvider = tokenProvider,
        baseRequest = baseReq,
        httpBackend = httpBackend,
      )
    }

  def makeZIO[R, E](
    connection: PubsubConnectionConfig,
    topic: String,
    serde: Serializer[R, E],
    httpBackend: GenericBackend[Task, Any],
    tokenProvider: Option[TokenProvider],
  ): Task[HttpPublisher[R, E]] =
    ZIO.fromEither(make(connection, topic, serde, httpBackend, tokenProvider)).mapError(new Throwable(_))

  def make[R, E](
    connection: PubsubConnectionConfig,
    topic: Topic[R, E],
    httpBackend: GenericBackend[Task, Any],
    tokenProvider: Option[TokenProvider],
  ): Either[String, HttpPublisher[R, E]] = make(connection, topic.name, topic.serde, httpBackend, tokenProvider)

  private def toBasePublishRequest(publishUri: Uri) = basicRequest
    .post(publishUri)
    .header(Header.contentType(MediaType.ApplicationJson))
    .mapResponse(_.flatMap(_.fromJson[MessageIds] match {
      case Right(MessageIds(id :: Nil)) => Right(id)
      case Right(MessageIds(Nil))       => Left("Missing message id in response")
      case Right(MessageIds(ids))       => Left(s"Received multiple message ids: $ids")
      case Left(err)                    => Left(err)
    }))

  private def toPublishUri(connection: PubsubConnectionConfig, topic: String) = connection match {
    case Cloud(project) =>
      Uri.parse(s"https://pubsub.googleapis.com/v1/projects/$project/topics/$topic:publish")
    case Emulator(project, host) =>
      Uri.parse(s"https://$host/v1/projects/$project/topics/$topic:publish")
  }
}
