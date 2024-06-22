package com.anymindgroup.pubsub.google

import java.util as ju
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import com.google.pubsub.v1.{ReceivedMessage, StreamingPullRequest, StreamingPullResponse}

import zio.stream.ZStream
import zio.test.Assertion.*
import zio.test.{
  Gen,
  Live,
  Spec,
  TestAspect,
  TestEnvironment,
  ZIOSpecDefault,
  assert,
  assertCompletes,
  assertTrue,
  assertZIO,
  check,
}
import zio.{Promise, Queue, Random, Ref, Schedule, Scope, ZIO, durationInt}
object StreamingPullSubscriberSpec extends ZIOSpecDefault {

  trait TestBidiStream[A, B] extends BidiStream[A, B] {
    override def send(request: A): Unit                 = ()
    override def closeSendWithError(t: Throwable): Unit = ()
    override def closeSend(): Unit                      = ()
    override def isSendReady(): Boolean                 = true
    override def cancel(): Unit                         = ()
  }

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("StreamingPullSubscriberSpec")(
    test("stream recovers on pull error") {
      val failUntilAttempt = 3

      def initStream(initCountRef: Ref[Int], ackedRef: AtomicReference[Vector[String]]) =
        ZStream.fromZIO {
          initCountRef.get
            .map(initCount => testBidiStream(failPull = initCount < (failUntilAttempt - 1), ackedRef = ackedRef))
            .tap(_ => initCountRef.updateAndGet(_ + 1))
        }

      for {
        initCountRef <- Ref.make(0)
        processedRef <- Ref.make(0)
        ackedRef      = new AtomicReference(Vector.empty[String])
        ackQueue     <- Queue.unbounded[(String, Boolean)]
        _ <- StreamingPullSubscriber
               .makeStream(
                 initStream(initCountRef, ackedRef),
                 ackQueue,
                 Schedule.recurs(failUntilAttempt + 1),
               )
               .mapZIO(e => processedRef.getAndUpdate(_ + 1) *> e._2.ack())
               .takeUntilZIO(_ => processedRef.get.map(_ > 0)) // take until first successfull processed
               .runCollect
        initCount      <- initCountRef.get
        processedCount <- processedRef.get
        ackedCount      = ackedRef.get().length
        _              <- assertTrue(processedCount == ackedCount)
      } yield assertTrue(initCount == failUntilAttempt)
    },
    test("stream recovers on ack error") {
      val failUntilAttempt = 3

      def initStream(initCountRef: Ref[Int], ackedRef: AtomicReference[Vector[String]]) =
        ZStream.fromZIO {
          initCountRef.get
            .map(t => testBidiStream(failSend = t < (failUntilAttempt - 1), ackedRef = ackedRef))
            .tap(_ => initCountRef.updateAndGet(_ + 1))
        }

      for {
        initCountRef <- Ref.make(0)
        processedRef <- Ref.make(0)
        ackedRef      = new AtomicReference(Vector.empty[String])
        ackQueue     <- Queue.unbounded[(String, Boolean)]
        _ <- StreamingPullSubscriber
               .makeStream(initStream(initCountRef, ackedRef), ackQueue, Schedule.recurs(5))
               .mapZIO(e => processedRef.getAndUpdate(_ + 1) *> e._2.ack())
               .takeUntil(_ => ackedRef.get().length > 0) // take until first successfull ack
               .runDrain
        initCount      <- initCountRef.get
        processedCount <- processedRef.get
        ackedCount      = ackedRef.get().length
        _              <- assertTrue(processedCount == ackedCount)
      } yield assertTrue(initCount == failUntilAttempt)
    },
    test("stream fails after maximum retry attempts") {
      val testBidiStream = new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
        override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
          hasNextImpl = throw new Throwable("Some error")
        )
      }

      val maxRetries = 5
      val schedule   = Schedule.recurs(maxRetries)

      def initStream(counter: Ref[Int]) =
        ZStream.fromZIO(counter.update(_ + 1)) *> ZStream.succeed(testBidiStream)

      for {
        retryCounter <- Ref.make(-1)
        queue        <- Queue.unbounded[(String, Boolean)]
        exit         <- StreamingPullSubscriber.makeStream(initStream(retryCounter), queue, schedule).runDrain.exit
        retries      <- retryCounter.get
        _            <- assert(retries)(equalTo(maxRetries))
      } yield assert(exit)(fails(anything))
    },
    test("all processed messages are acked or nacked on interruption") {
      check(Gen.int(1, 10000), Gen.boolean, Gen.int(1, 50)) { (interruptOnCount, interruptWithFailure, parralelism) =>
        for {
          processedRef     <- Ref.make(Vector.empty[String])
          ackedRef          = new AtomicReference(Vector.empty[String])
          nackedRef         = new AtomicReference(Vector.empty[String])
          ackQueue         <- Queue.unbounded[(String, Boolean)]
          interruptPromise <- Promise.make[Throwable, Unit]
          _ <- StreamingPullSubscriber
                 .makeStream(
                   ZStream.succeed(testBidiStream(ackedRef = ackedRef, nackedRef = nackedRef)),
                   ackQueue,
                   Schedule.recurs(5),
                 )
                 .mapZIOPar(parralelism) { case (msg, reply) =>
                   (for {
                     c <- processedRef.updateAndGet(_ :+ msg.getAckId())
                     _ <- Live.live(Random.nextBoolean).flatMap {
                            case true  => reply.ack()
                            case false => reply.nack()
                          }
                   } yield c).uninterruptible.flatMap {
                     case c if c.size >= interruptOnCount =>
                       if (interruptWithFailure) interruptPromise.fail(new Throwable("interrupt with error"))
                       else interruptPromise.succeed(())
                     case _ => ZIO.unit
                   }
                 }
                 .interruptWhen(interruptPromise)
                 .runDrain
                 .exit
          processedAckIds  <- processedRef.get
          ackedAndNackedIds = ackedRef.get ++ nackedRef.get
          _                <- assertZIO(ackQueue.size)(equalTo(0))
          _                <- assertTrue(processedAckIds.size >= interruptOnCount)
          _                <- assertTrue(ackedAndNackedIds.size >= interruptOnCount)
          _                <- assert(processedAckIds)(hasSameElements(ackedAndNackedIds))
        } yield assertCompletes
      }
    } @@ TestAspect.samples(20),
    test("server stream is canceled on interruption (standalone)") {
      val cancelled = new ju.concurrent.atomic.AtomicBoolean(false)
      val lock      = new AnyRef
      val testBidiStream = new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
        override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
          // hasNext that never returns until cancelled
          hasNextImpl = {
            lock.synchronized(while (!cancelled.get()) lock.wait())
            false
          }
        )
        override def cancel(): Unit = {
          cancelled.set(true)
          lock.synchronized(lock.notify())
        }
      }

      for {
        _ <- Live.live(
               StreamingPullSubscriber
                 .makeServerStream(testBidiStream)
                 .timeout(500.millis)
                 .runDrain
             )
      } yield assertTrue(cancelled.get)
    } @@ TestAspect.timeout(5.seconds),
    test("server stream is canceled on interruption when running with ack stream") {
      val cancelled = new ju.concurrent.atomic.AtomicBoolean(false)
      val lock      = new AnyRef
      val testBidiStream = new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
        override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
          // hasNext that never returns until cancelled
          hasNextImpl = {
            lock.synchronized(while (!cancelled.get()) lock.wait())
            false
          }
        )
        override def cancel(): Unit = {
          cancelled.set(true)
          lock.synchronized(lock.notify())
        }
      }

      for {
        queue <- Queue.unbounded[(String, Boolean)]
        _ <- Live.live(
               StreamingPullSubscriber
                 .makeStream(ZStream.succeed(testBidiStream), queue, Schedule.forever)
                 .timeout(500.millis)
                 .runDrain
             )
      } yield assertTrue(cancelled.get)
    },
  ) @@ TestAspect.timeout(60.seconds)

  def testBidiStream(
    failSend: Boolean = false,
    failPull: Boolean = false,
    ackedRef: AtomicReference[Vector[String]] = new AtomicReference[Vector[String]](Vector.empty),
    nackedRef: AtomicReference[Vector[String]] = new AtomicReference[Vector[String]](Vector.empty),
  ): BidiStream[StreamingPullRequest, StreamingPullResponse] =
    new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
      override def send(r: StreamingPullRequest): Unit =
        if (failSend) throw new Throwable("failed ack")
        else {
          val _ = ackedRef.updateAndGet(_ ++ r.getAckIdsList.asScala.toVector)

          val nackIds = r.getModifyDeadlineAckIdsList.asScala.toVector
          // deadline needs to be set to 0 to nack a message
          val deadlines = r.getModifyDeadlineSecondsList.asScala.toVector.filter(_ == 0)

          // both have to have the same size, otherwise it's not a valid request
          if (nackIds.length != deadlines.length) {
            throw new Throwable("getModifyDeadlineAckIdsList / getModifyDeadlineSecondsList don't match in size")
          }

          val _ = nackedRef.updateAndGet(_ ++ nackIds)
        }

      override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
        hasNextImpl = if (failPull) throw new Throwable("fail pull") else true
      )
    }

  private def streamingPullResIterator(hasNextImpl: => Boolean): ju.Iterator[StreamingPullResponse] =
    new java.util.Iterator[StreamingPullResponse] {
      override def hasNext(): Boolean = hasNextImpl
      // return one message by default
      override def next(): StreamingPullResponse =
        StreamingPullResponse
          .newBuilder()
          .addReceivedMessages(ReceivedMessage.newBuilder().setAckId(ju.UUID.randomUUID().toString()).build())
          .build()
    }
}
