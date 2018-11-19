package akka.stream.instrumentation

import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.stream._
import akka.stream.impl.Buffer
import akka.stream.impl.fusing.GraphInterpreter
import akka.stream.impl.fusing.GraphInterpreter.Connection
import akka.stream.stage.{GraphStage, GraphStageLogic}
import kamon.Kamon
import kamon.context.Context
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._

import scala.concurrent.Promise
import scala.util.Try

import scala.collection.JavaConverters._

trait ContextHolder {
  def setContext(context: Context): Unit
  def getContext: Context
}

object ContextHolder {
  def apply: ContextHolder = new ContextHolder {
    var context: Context = _
    var scope: Context = _

    override def setContext(context: Context): Unit = this.context = context

    override def getContext: Context = context
  }
}

object InstrumentedBuffer {
  //TODO pass in materializer
  //For buffering in custom stages
  def apply[T](inner: Buffer[T]): InstrumentedBuffer[T] = new InstrumentedBuffer(Buffer[T](10, 1000))
}
class InstrumentedBuffer[T](inner: Buffer[T]) extends Buffer[T] {
  //TODO pass on buffer params from creation point
  private val contextBuffer = Buffer[Context](10, 100)

  override def capacity: Int = inner.capacity

  override def used: Int = inner.used

  override def isFull: Boolean = inner.isFull

  override def isEmpty: Boolean = inner.isEmpty

  override def nonEmpty: Boolean = inner.nonEmpty

  override def enqueue(elem: T): Unit = {
    inner.enqueue(elem)
    contextBuffer.enqueue(Kamon.currentContext())
  }

  override def dequeue(): T = {
    val elem = inner.dequeue()
    Kamon.storeContext(contextBuffer.dequeue())
    elem
  }

  override def peek(): T = inner.peek()

  override def clear(): Unit = {
    contextBuffer.clear()
    inner.clear()
  }

  override def dropHead(): Unit = {
    contextBuffer.dropHead()
    inner.dropHead()
  }

  override def dropTail(): Unit = {
    contextBuffer.dropTail()
    inner.dropTail()
  }
}

@Aspect
class StageInterpretationInstrumentation {

  @DeclareMixin("akka.stream.impl.fusing.GraphInterpreter.Connection")
  def mixinInstrumentationIntoConnection: ContextHolder = ContextHolder.apply

  @After("execution(akka.stream.impl.fusing.GraphInterpreter.Connection.new(..)) && this(connection)")
  def aroundConnectionConstructor(connection: Connection): Unit = {
    connection.asInstanceOf[ContextHolder].setContext(Context.Empty)
  }

  @DeclareMixin("akka.stream.stage.GraphStage+")
  def mixinInstrumentationIntoSourceShapedStages: ContextHolder = ContextHolder.apply
  //where shape is instanceOf SourceShape

  @After("execution(akka.stream.stage.GraphStage+.new(..)) && this(stage)")
  def aroundConnectionConstructor(stage: GraphStage[_]): Unit = {
    stage.asInstanceOf[ContextHolder].setContext(Kamon.currentContext())
  }



  @Around("execution(* akka.stream.stage.GraphStageLogic+.grab(..)) && this(logic) && args(inlet)")
  def aroundGrab(pjp: ProceedingJoinPoint, inlet: Inlet[_], logic: GraphStageLogic): Any = {
    val connection = connectionForPort(logic, inlet.asInstanceOf[InPort].id)

    Kamon.storeContext(connection.asInstanceOf[ContextHolder].getContext)
    pjp.proceed()
  }

  @Around("execution(* akka.stream.stage.GraphStageLogic+.push(..)) && this(logic) && args(outlet, elem)")
  def aroundPush(pjp: ProceedingJoinPoint, outlet: Outlet[_], elem: Any, logic: GraphStageLogic): Any = {
    val connection = connectionForPort(logic, outlet.asInstanceOf[OutPort].id + logic.inCount)

    //TODO cache this

    val sourceStageCtx = Try {
      val graphStageField= logic.getClass.getDeclaredField("$outer")
      graphStageField.setAccessible(true)
      val stage = graphStageField.get(logic)
      val shapeField = stage.getClass.getDeclaredField("shape")
      shapeField.setAccessible(true)
      val shape = shapeField.get(stage)
      if(shape.isInstanceOf[SourceShape[_]]) {
        stage.asInstanceOf[ContextHolder].getContext
      } else {
        Kamon.currentContext()
      }
    }

    //If its a source, theres no connection context, use one comming from stage, captured during stage construction
    val propagatedCtx = sourceStageCtx.getOrElse(Kamon.currentContext())

    connection.asInstanceOf[ContextHolder].setContext(propagatedCtx)
    pjp.proceed()
  }


  private def connectionForPort(logic: GraphStageLogic, portId: Int): Connection = {
    val connectionMapping = logic.getClass.getMethod("portToConn")
    val connections = connectionMapping.invoke(logic).asInstanceOf[Array[Connection]]
    connections(portId)
  }
}

/*
* SubstreamSource element is created with first element present which is always pulled,
* all others are synchronous with push, preserving context using AsyncCallback instrumentation
* */
@Aspect
class StreamOfStreamsInstrumentation {

  @DeclareMixin("akka.stream.stage.GraphStageLogic.SubSourceOutlet+")
  def mixinInstrumentationIntoSubstreamSource: ContextHolder = ContextHolder.apply

  @After("execution(akka.stream.stage.GraphStageLogic.SubSourceOutlet+.new(..)) && this(ssource)")
  def afterSourceOutletConstructor(ssource: Any): Unit = {
    ssource.asInstanceOf[ContextHolder].setContext(Kamon.currentContext())
  }

  @Around("execution(* akka.stream.stage.GraphStageLogic.SubSourceOutlet+.onPull(..)) && this(ssource)")
  def aroundOnNextExecute(pjp: ProceedingJoinPoint, ssource: Any): Any = {
    val firstElemContext = ssource.asInstanceOf[ContextHolder].getContext
    //TODO cache method
    val firstPush = ssource.getClass.getMethod("firstPush").invoke(ssource).asInstanceOf[Boolean]
    if (firstPush) Kamon.withContext(firstElemContext) {
      pjp.proceed()
    } else {
      pjp.proceed()
    }
  }

  @DeclareMixin("akka.stream.stage.GraphStageLogic.SubSinkInlet+")
  def mixinInstrumentationIntoSubstreamSink: ContextHolder = ContextHolder.apply

  @After("execution(akka.stream.stage.GraphStageLogic.SubSinkInlet+.new(..)) && this(ssink)")
  def afterSinkInletConstructor(ssink: Any): Unit = {
    ssink.asInstanceOf[ContextHolder].setContext(Kamon.currentContext())
  }

  @Around("execution(* akka.stream.stage.GraphStageLogic.SubSinkInlet+.grab(..)) && this(ssink)")
  def aroundSinkInletGrab(pjp: ProceedingJoinPoint, ssink: Any): Any = {
    val sinkCreationPointContext = ssink.asInstanceOf[ContextHolder].getContext
    Kamon.storeContext(sinkCreationPointContext)
    pjp.proceed()
  }
}

@Aspect
class BoundaryInstrumentation {
  @DeclareMixin("akka.stream.impl.fusing.ActorGraphInterpreter.BatchingActorInputBoundary.OnNext")
  def mixinInstrumentationToOnNext: ContextHolder = ContextHolder.apply

  @After("execution(akka.stream.impl.fusing.ActorGraphInterpreter.BatchingActorInputBoundary.OnNext.new(..)) && this(msg)")
  def aroundOnNextConstructor(msg: AnyRef): Unit = {
    msg.asInstanceOf[ContextHolder].setContext(Kamon.currentContext())
  }

  @Around("execution(* akka.stream.impl.fusing.ActorGraphInterpreter.BatchingActorInputBoundary.OnNext.execute(..)) && this(msg)")
  def aroundOnNextExecute(pjp: ProceedingJoinPoint, msg: AnyRef): Any = {
    val ctx = msg.asInstanceOf[ContextHolder].getContext
    Kamon.withContext(ctx) {
      pjp.proceed()
    }
  }

  @DeclareMixin("akka.stream.impl.fusing.ActorGraphInterpreter.BatchingActorInputBoundary")
  def mixinInstrumentationIntoBatchingBoundary: ContextHolder = ContextHolder.apply

}

@Aspect
class BufferingStagesInstrumentation {

  @Around("execution(* akka.stream.stage.GraphStageLogic+.preStart(..)) && this(logic)")
  def aroundStagePreStart(pjp: ProceedingJoinPoint, logic: GraphStageLogic): Any = {
    val ret = pjp.proceed()
    Try(logic.getClass.getDeclaredField("buffer")) foreach { bufferField =>
      bufferField.setAccessible(true)
      val innerBuffer = bufferField.get(logic).asInstanceOf[Buffer[Any]]
      val wrappedBuffer = new InstrumentedBuffer[Any](innerBuffer)
      bufferField.set(logic, wrappedBuffer)
    }
    ret
  }
}


@Aspect
class AsyncCallbackInstrumentation {

  @Around("execution(* akka.stream.stage.GraphStageLogic.ConcurrentAsyncCallback.onAsyncInput(..)) && this(callback) && args(event, promise)")
  def aroundAsyncInvocation(pjp: ProceedingJoinPoint, callback: Any, event: Any, promise: Promise[Done]): Unit = {
    val stageField = callback.getClass.getDeclaredField("$outer")
    stageField.setAccessible(true)
    val stage = stageField.get(callback)
    val interpreter = stage.getClass
      .getMethod("interpreter").invoke(stage)
      .asInstanceOf[GraphInterpreter]

    val handlerField = callback.getClass.getDeclaredField("handler")
    handlerField.setAccessible(true)
    val handler: Any => Unit = handlerField.get(callback).asInstanceOf[Any => Unit]

    val contextAwareHandler = {
      val invocationPointContext = Kamon.currentContext()
      evt: Any => Kamon.withContext(invocationPointContext) {
        handler(evt)
      }
    }

    interpreter.onAsyncInput(stage.asInstanceOf[GraphStageLogic], event, promise, contextAwareHandler)
  }

  //TODO instrument callback event wrappers, if stream is not materialized before offering, context is lost on first events
  @DeclareMixin("akka.stream.stage.GraphStageLogic.ConcurrentAsyncCallback.Event")
  def mixinInstrumentationIntoCallbackEvent: ContextHolder = ContextHolder.apply

  @After("execution(akka.stream.stage.GraphStageLogic.ConcurrentAsyncCallback.Event.new(..)) && this(event)")
  def afterEventConstructor(event: Any): Unit = {
    event.asInstanceOf[ContextHolder].setContext(Kamon.currentContext())
  }

  private def getPrivateField(name: String, obj: Any): Any = {
    val field = obj.getClass.getDeclaredField(name)
    field.setAccessible(true)
    field.get(obj)
  }

  /*
  * Replaces original ConcurrentAsyncCallback.onStart in order to preserve context when
  * submitting events before Callback started.
  * If there are pending events, `onAsyncInput` is invoked with context from that event
  * captured on event creation.
  * */
  //TODO nasty reflection here
  @Around("execution(* akka.stream.stage.GraphStageLogic.ConcurrentAsyncCallback.onStart(..)) && this(callback)")
  def replaceCallbackOnStart(callback: AnyRef): Unit = {
    val currentState = getPrivateField("currentState", callback).asInstanceOf[AtomicReference[Any]]
    //This one could also be potentially uninitialized
    val NoPendingEvents = getPrivateField("NoPendingEvents", callback)
    val PendingClass = callback.getClass.getDeclaredField("Pending$module").getType
    val Initialized = {
      //force lazy init
      val method = callback.getClass.getDeclaredMethod("Initialized$lzycompute$1")
      method.setAccessible(true)
      method.invoke(callback)
      getPrivateField("Initialized$module", callback)
    }

    val onAsyncInputMethod = {
      val method = {
        val p1: Class[_] = new Object().getClass
        val p2: Class[_] = scala.concurrent.Promise[Any].getClass.getInterfaces.head.getInterfaces.head
        callback.getClass.getDeclaredMethod("onAsyncInput", p1, p2)
      }

      method.setAccessible(true)
      method
    }

    val previousState = currentState.getAndSet(NoPendingEvents)

    val onAsyncInput = (event: AnyRef, promise: Promise[_]) => {
      onAsyncInputMethod.invoke(callback, event, promise)
    }

    val pendingEvents = getPrivateField("pendingEvents", previousState)

    //TODO ARGH!!@!#!@, fix inner Pending class nesting :/
    //if(previousState.getClass == PendingClass) {
    if(PendingClass.getName.contains(previousState.getClass.getName)) {
      pendingEvents.asInstanceOf[List[_]].reverse.foreach { event =>
        val e = getPrivateField("e", event).asInstanceOf[AnyRef]
        val promise = getPrivateField("handlingPromise", event).asInstanceOf[Promise[_]]
        val eventCreationPointContext = event.asInstanceOf[ContextHolder].getContext
        Kamon.withContext(eventCreationPointContext) {
          onAsyncInput(e, promise)
        }
      }
    } else {
      throw new IllegalStateException(s"Unexpected callback state [$previousState]")
    }

    // in the meantime more callbacks might have been queued (we keep queueing them to ensure order)
    if (!currentState.compareAndSet(NoPendingEvents, Initialized)) {
      // state guaranteed to be still Pending
      val onStartMethod = callback.getClass.getDeclaredMethod("onStart")
      onStartMethod.setAccessible(true)
      onStartMethod.invoke(callback)
    }
  }

}

@Aspect
class SourceInstrumentation {
  /*
  * - single
  * - fromGraph
  * - fromFutureSource
  * - fromFuture
  * - unfold ???
  *
  * Source graph stages GraphStage[SourceShape]
  *  - SingleSource
  *  - FutureSource
  *
  *  - FutureFlattenSource ???
  *
  *
  *   SingleSource
      UnfoldResourceSourceAsync  - async
      FutureSource  - async but if ctx present, callback instrumentation will carry it, wraps push around emit
      SubSource     - pass, stream of streams
      JavaStreamSource  - simple
      UnfoldResourceSource - callback might carry it
      FailedSource   - skip
      RestartWithBackoffSource  - ??? no handlers, i guess safe to skip
  * */
}