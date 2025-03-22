package com.anymindgroup.pubsub.http

import com.anymindgroup.http.httpBackendScoped
import com.anymindgroup.pubsub.PubsubConnectionConfig
import sttp.capabilities.Effect
import sttp.client4.{Backend, GenericRequest, Response}
import sttp.monad.MonadError

import zio.{Scope, Task, ZIO}

object EmulatorBackend:
  def apply(backend: Backend[Task], config: PubsubConnectionConfig.Emulator): Backend[Task] =
    new Backend[Task] {
      override def send[T](req: GenericRequest[T, Any & Effect[Task]]): Task[Response[T]] =
        backend.send(req.method(req.method, req.uri.scheme("http").host(config.host).port(config.port)))

      override def close(): Task[Unit] = backend.close()

      override def monad: MonadError[Task] = backend.monad
    }

  def withDefaultBackend(
    config: PubsubConnectionConfig.Emulator
  ): ZIO[Scope, Throwable, Backend[Task]] =
    httpBackendScoped().map(backend => apply(backend, config))
