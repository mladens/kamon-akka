/*
 * =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package akka.kamon.instrumentation.advisor

import akka.actor.{ActorRef, ActorSystem, Cell}
import akka.kamon.instrumentation.{ActorMonitor, RouterMonitor}
import akka.routing.RoutedActorCell
import kamon.agent.libs.net.bytebuddy.asm.Advice._
import kamon.akka.instrumentation.mixin._

trait ActorInstrumentationSupport {
  def actorInstrumentation(cell: Cell): ActorMonitor = cell.asInstanceOf[ActorInstrumentationAware].actorInstrumentation
}

/**
  * Advisor for akka.actor.ActorCell::constructor
  */
class ActorCellConstructorAdvisor
object ActorCellConstructorAdvisor {
  @OnMethodExit
  def onExit(@This cell: Cell,
             @Argument(0) system: ActorSystem,
             @Argument(1) ref: ActorRef,
             @Argument(4) parent: ActorRef): Unit = {

    cell.asInstanceOf[ActorInstrumentationAware].setActorInstrumentation(ActorMonitor.createActorMonitor(cell, system, ref, parent, actorCellCreation = true))
  }
}

/**
  * Advisor for akka.actor.ActorCell::invoke
  */
class InvokeMethodAdvisor
object InvokeMethodAdvisor extends ActorInstrumentationSupport {

  @OnMethodEnter
  def onEnter(@This cell: Cell,
              @Argument(0) envelope: Object): TimestampedActiveSpan = {
    // FIXME The `envelopeContext orElse Empty` code is a workaround to prevent NullPointer when the
    // Envelope come from a RoutedActorCell in some cases as in the test
    // `RouterMetricsSpec."clean up the associated recorder when the pool router is stopped"`
    val timestampedContinuation = Option(envelope.asInstanceOf[InstrumentedEnvelope].timestampedContinuation()).getOrElse(TimestampedContinuation.Empty)
    val continuation = if (timestampedContinuation.continuation == null) TimestampedContinuation.Empty else timestampedContinuation
    actorInstrumentation(cell).processMessageStart(continuation)
  }

  @OnMethodExit(onThrowable = classOf[Throwable])
  def onExit(@This cell: Cell,
             @Enter timestampedActiveSpan: TimestampedActiveSpan,
             @Argument(0) envelope: Object,
             @Thrown failure: Throwable): Unit = {


    actorInstrumentation(cell).processMessageEnd(timestampedActiveSpan, envelope.asInstanceOf[InstrumentedEnvelope].timestampedContinuation())

    if (failure != null)
      actorInstrumentation(cell).processFailure(failure)
  }
}

/**
  * Advisor for akka.actor.ActorCell::handleInvokeFailure
  */
class HandleInvokeFailureMethodAdvisor
object HandleInvokeFailureMethodAdvisor extends ActorInstrumentationSupport {

  @OnMethodEnter
  def onEnter(@This cell: Cell,
              @Argument(1) failure: Throwable): Unit = {
    actorInstrumentation(cell).processFailure(failure)
  }
}

/**
  * Advisor for akka.actor.ActorCell::sendMessage
  * Advisor for akka.actor.UnstartedCell::sendMessage
  */
class SendMessageMethodAdvisor
object SendMessageMethodAdvisor extends ActorInstrumentationSupport {
  @OnMethodEnter
  def onEnter(@This cell: Cell,
              @Argument(0) envelope: Object): Unit = {
    envelope.asInstanceOf[InstrumentedEnvelope].setTimestampedContinuation(actorInstrumentation(cell).captureEnvelopeContext())
  }
}

/**
  * Advisor for akka.actor.ActorCell::stop
  */
class StopMethodAdvisor
object StopMethodAdvisor extends ActorInstrumentationSupport {
  @OnMethodExit
  def onExit(@This cell: Cell): Unit = {
    actorInstrumentation(cell).cleanup()

    // The Stop can't be captured from the RoutedActorCell so we need to put this piece of cleanup here.
    if (cell.isInstanceOf[RoutedActorCell]) {
      cell.asInstanceOf[RouterInstrumentationAware].routerInstrumentation.cleanup()
    }
  }
}

/**
  * Advisor for akka.actor.UnstartedCell::constructor
  */
class RepointableActorCellConstructorAdvisor
object RepointableActorCellConstructorAdvisor {
  @OnMethodExit
  def onExit(@This cell: Cell,
             @Argument(0) system: ActorSystem,
             @Argument(1) ref: ActorRef,
             @Argument(3) parent: ActorRef): Unit = {

    cell.asInstanceOf[ActorInstrumentationAware].setActorInstrumentation(ActorMonitor.createActorMonitor(cell, system, ref, parent, actorCellCreation = false))
  }
}

/**
  * Advisor for akka.routing.RoutedActorCell::constructor
  */
class RoutedActorCellConstructorAdvisor
object RoutedActorCellConstructorAdvisor {
  @OnMethodExit
  def onExit(@This cell: Cell): Unit = {
    cell.asInstanceOf[RouterInstrumentationAware].setRouterInstrumentation(RouterMonitor.createRouterInstrumentation(cell))
  }
}

/**
  * Advisor for akka.routing.RoutedActorCell::sendMessage
  */
class SendMessageMethodAdvisorForRouter
object SendMessageMethodAdvisorForRouter {

  def routerInstrumentation(cell: Cell): RouterMonitor = cell.asInstanceOf[RouterInstrumentationAware].routerInstrumentation

  @OnMethodEnter
  def onEnter(@This cell: Cell): Long = {
    routerInstrumentation(cell).processMessageStart()
  }

  @OnMethodExit
  def onExit(@This cell: Cell,
             @Enter timestampBeforeProcessing: Long): Unit = {

    routerInstrumentation(cell).processMessageEnd(timestampBeforeProcessing)
  }
}
