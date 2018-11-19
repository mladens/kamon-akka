package kamon.akka

import akka.NotUsed
import akka.actor.{ActorSystem, Status}
import akka.stream.{ActorMaterializer, FlowShape, OverflowStrategy}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, Sink, Source}
import akka.testkit.{ImplicitSender, TestKit}
import kamon.Kamon
import kamon.context.{Context, Key}
import kamon.context.Storage.Scope
import kamon.testkit.MetricInspection
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import org.scalatest.time.{Millis, Seconds, Span}

class StreamSpec extends TestKit(ActorSystem("ActorMetricsSpec")) with WordSpecLike with MetricInspection with Matchers
  with BeforeAndAfterAll with ImplicitSender with Eventually with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(2, Seconds)), interval = scaled(Span(50, Millis)))

  val TraceId: Key[Option[String]] = Key.broadcastString("TraceId")

  def inContext[T](traceId: String)(fn: => T): T = Kamon.withContextKey(TraceId, Some(traceId))(fn)
  def currentTraceId(): Option[String] = Kamon.currentContext().get(TraceId)
  def setTraceId(id: String): Scope = Kamon.storeContext(Kamon.currentContext().withKey(TraceId, Some(id)))

  implicit lazy val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext =  scala.concurrent.ExecutionContext.global

  def currThread = s"${Thread.currentThread().getId}"
  def setCtx(i: Int): Int = {
    setTraceId(i.toString)
    i
  }


  "Stream instrumentation" should {

    "pure source" in {
      Kamon.withContext(Context.Empty) {
        setTraceId("outter")
        val res = Source
          .fromFuture(Future(3))
          .map(_ => currentTraceId())
          .toMat(Sink.seq)(Keep.right)
          .run()

        res.futureValue.head === "outter"
      }
    }

    "group by" in {
      val nums = Seq(1,2,3,1,2,3,1,2,3,1,2,3)
      val result = Source.fromIterator(() => nums.toIterator)
        .map(setCtx)
        .groupBy(Int.MaxValue, identity)
        .map(i => {
          currentTraceId() should be (Some(i.toString))
          i
        })
        .mergeSubstreamsWithParallelism(3)
        .map(i => {
          currentTraceId() should be (Some(i.toString))
          currentTraceId()
        })
        .toMat(Sink.seq)(Keep.right)
        .run()

      result.futureValue should have size nums.size
      result.futureValue.distinct.flatten should contain allElementsOf nums.distinct.map(_.toString)
    }

    "flatMap" should {
      "merge" in {
        val result = Source(List(10, 20, 30, 40))
          .map(setCtx)
          .flatMapMerge(2, i => {
            currentTraceId() should be (Some(i.toString))
            Source(List(i+1, i+2))
          })
          .map(i => {
            val sourceTrace = (i / 10) * 10
            currentTraceId() should be (Some(sourceTrace.toString))
            i
          })
          .toMat(Sink.seq)(Keep.right)
          .run()

        result.futureValue should have size 8
      }

      "override substream source context" in {
        val result = Source(List(10, 20, 30, 40))
          .map(setCtx)
          .flatMapMerge(2, i => {
            currentTraceId() should be (Some(i.toString))
            Source(List(i+1, i+2))
          })
          .map(i => {
            val sourceTrace = (i / 10) * 10
            currentTraceId() should be (Some(sourceTrace.toString))
            setTraceId(i.toString)
            i
          }).map(i => {
            currentTraceId() should be (Some(i.toString))
          })
          .toMat(Sink.seq)(Keep.right)
          .run()

        result.futureValue should have size 8
      }

      "concat" in { //same as merge with breadth 1
        val result = Source(List(10, 20, 30, 40))
          .map(setCtx)
          .flatMapConcat(i => {
            currentTraceId() should be (Some(i.toString))
            Source(List(i+1, i+2))
          })
          .map(i => {
            val sourceTrace = (i / 10) * 10
            currentTraceId() should be (Some(sourceTrace.toString))
            i
          })
          .toMat(Sink.seq)(Keep.right)
          .run()

        result.futureValue should have size 8
      }
    }

    "mapconcat" in {
      inContext("init") {
        val result = Source(1 to 5)
          .map(setCtx)
          .mapConcat(i => i*1000 until i*1000+10)
          .map(i => {
            val sourceElem = i / 1000
            currentTraceId() should be (Some(sourceElem.toString))
            i
          })
          .runWith(Sink.seq)
        result.futureValue should have size 5 * 10
      }
    }

    "map" in {
      inContext("init") {
        val nums = 1.until(5)
        val result = Source(nums)
          .map(setCtx)
          .map { n =>
            val trid = currentTraceId()
            trid should be (Some(n.toString))
            trid
          }.runWith(Sink.seq)

        result.futureValue.flatten should have size 4
        result.futureValue.flatten should contain allElementsOf nums.map(_.toString)
        currentTraceId() should be (Some("init"))
      }
    }

    "map async" should {

      "construction point ctx" in {
        inContext("init") {
          val res = Source(1 to 10)
            .async
            .map(_ => currentTraceId() )
            .runWith(Sink.seq)
            .futureValue
            .flatten

          res should have size 10
          res.distinct should contain only "init"
        }
      }

      "stage constructed ctx" in {
        inContext("init") {
          val nums = List(1,2,3,4,5)
          val res = Source(nums)
            .map(setCtx)
            .async
            .map(_ => currentTraceId())
            .runWith(Sink.seq)
            .futureValue
            .flatten

          res should have size 5
          res should contain allElementsOf nums.map(_.toString)
        }
      }
    }

    "Async boundary after buffering" in {
      //buffer elements with backpressure, then do async, context is lost
      inContext("outer") {
        val nums = 1.until(100)
        val res = Source(nums)
          .map(setCtx)
          .async
          .buffer(10, OverflowStrategy.dropHead)
          .map { n =>
            currentTraceId() should be (Some(n.toString))
            currentTraceId()
          }.runWith(Sink.seq)

        res.futureValue.flatten should have size nums.size
        currentTraceId() should be (Some("outer"))
      }
    }

    "no backpressure source with buffering " in {
      inContext("outer") {
        val (ref, sink) = Source.actorRef[String](100, OverflowStrategy.dropNew)
          .map(m => {
            setTraceId(m)
            m
          })
          .async
          .buffer(10, OverflowStrategy.dropHead)
          .map { n =>
            currentTraceId should be (Some(n))
            currentTraceId
          }.toMat(Sink.seq)(Keep.both).run()

        val msgs = (1 to 5).map(i => s"msg-$i")
        msgs.foreach(m => ref ! m)

        ref ! Status.Success("done")

        sink.futureValue.flatten should have size 5
        sink.futureValue.flatten should contain allElementsOf(msgs)
      }
    }

    "graph" in {
      def broadcastAndMerge[I,O] (flow: Flow[I, O, NotUsed]): Flow[I, O, NotUsed] =
        Flow.fromGraph[I, O, NotUsed](GraphDSL.create(flow) { implicit builder => flow =>
          import GraphDSL.Implicits._

          val broadcast = builder.add(Broadcast[I](5))
          val merge = builder.add(Merge[I](5))
          def fn = builder.add(Flow[I].async)

          // dummy graph to illustrate, that the element can take several paths
          broadcast ~> fn ~> merge
          broadcast ~> fn ~> merge
          broadcast ~> fn ~> merge
          broadcast ~> fn ~> merge
          broadcast ~> fn ~> merge
                             merge ~> flow

          FlowShape(broadcast.in, flow.out)
        })

      val nums = List(1,2,3,4,5)
      val (queue, sink) = Source.queue[Int](nums.size, OverflowStrategy.fail)
        .via(broadcastAndMerge(Flow.fromFunction((_: Int) => currentTraceId())))
        .toMat(Sink.seq)(Keep.both)
        .run()

      inContext("outer") {
        val a = nums.map { num =>
          setTraceId(num.toString)
          queue.offer(num)
        }.toSeq

        Future.sequence(a).futureValue
        queue.complete()
        val result = sink.futureValue
        // each element is broadcasted and merged 5 times --> expect 5 times size
        result.flatten should have size  5 * nums.size
        result.flatten should contain allElementsOf nums.map(_.toString)
      }
    }

   /* "throtle" in {
      //Throtled elements get scheduled on interpreter as runnable invoking asyncCallback
      //Executor instrumentation will capture context and provide it to callback instrumentation

      implicit val patienceConfig: PatienceConfig = PatienceConfig(
        timeout = scaled(Span(12, Seconds)),
        interval = scaled(Span(1, Seconds))
      )

      val nums = 1 to 1000
      val res = Source(nums)
        .map(setCtx)
        .throttle(100, 1 seconds)
        .map { i =>
          currentTraceId() should be (Some(i.toString))
        }
        .runWith(Sink.seq)
      res.futureValue.flatten should contain allElementsOf(nums.map(_.toString))
    }*/

  }

}
