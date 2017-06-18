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

package akka.kamon.instrumentation

import akka.actor.{ActorRef, ActorSystem, Cell}
import akka.kamon.instrumentation.ActorMonitors.{TrackedActor, TrackedRoutee}
import io.opentracing.NoopActiveSpanSource.NoopActiveSpan
import kamon.Kamon
import kamon.akka.Metrics
import kamon.akka.Metrics.{ActorGroupMetrics, ActorMetrics, RouterMetrics}
import kamon.akka.instrumentation.mixin.{TimestampedActiveSpan, TimestampedContinuation}

trait ActorMonitor {
  def captureEnvelopeContext(): TimestampedContinuation
  def processMessageStart(envelopeContext: TimestampedContinuation): TimestampedActiveSpan
  def processMessageEnd(timestampedActiveSpan: TimestampedActiveSpan, envelopeContext: TimestampedContinuation): Unit
  def processFailure(failure: Throwable): Unit
  def cleanup(): Unit
}

object ActorMonitor {

  def createActorMonitor(cell: Cell, system: ActorSystem, ref: ActorRef, parent: ActorRef, actorCellCreation: Boolean): ActorMonitor = {
    val cellInfo = CellInfo.cellInfoFor(cell, system, ref, parent, actorCellCreation)

    if (cellInfo.isRouter)
      ActorMonitors.ContextPropagationOnly
    else {
      if (cellInfo.isRoutee && cellInfo.isTracked)
        createRouteeMonitor(cellInfo)
      else
        createRegularActorMonitor(cellInfo)
    }
  }

  def createRegularActorMonitor(cellInfo: CellInfo): ActorMonitor = {
    if (cellInfo.isTracked || cellInfo.trackingGroups.nonEmpty) {
      val actorMetrics = if (cellInfo.isTracked) Some(Metrics.forActor(cellInfo.path)) else None
      new TrackedActor(actorMetrics, trackingGroupMetrics(cellInfo), cellInfo.actorCellCreation)
    } else {
      ActorMonitors.ContextPropagationOnly
    }
  }

  def createRouteeMonitor(cellInfo: CellInfo): ActorMonitor = {
    val routerMetrics = Metrics.forRouter(cellInfo.path)
    new TrackedRoutee(routerMetrics, trackingGroupMetrics(cellInfo), cellInfo.actorCellCreation)
  }

  private def trackingGroupMetrics(cellInfo: CellInfo): Seq[ActorGroupMetrics] = {
    cellInfo.trackingGroups.map { groupName =>
      Metrics.forGroup(groupName)
    }
  }
}

object ActorMonitors {

  val ContextPropagationOnly = new ActorMonitor {
    def captureEnvelopeContext(): TimestampedContinuation =
      TimestampedContinuation(0L, Kamon.activeSpanContinuation())

    def processMessageStart(envelopeContext: TimestampedContinuation): TimestampedActiveSpan = {
      val continuation = envelopeContext.continuation
      TimestampedActiveSpan(0L, if(continuation != null) continuation.activate() else NoopActiveSpan.INSTANCE)
    }

    def processMessageEnd(timestampedActiveSpan: TimestampedActiveSpan, envelopeContext: TimestampedContinuation): Unit =
      timestampedActiveSpan.activeSpan.deactivate()

    def processFailure(failure: Throwable): Unit = {}
    def cleanup(): Unit = {}

  }

  class TrackedActor(actorMetrics: Option[ActorMetrics], groupMetrics: Seq[ActorGroupMetrics], actorCellCreation: Boolean)
    extends GroupMetricsTrackingActor(groupMetrics, actorCellCreation) {

    override def captureEnvelopeContext(): TimestampedContinuation = {
      actorMetrics.foreach { am =>
        am.mailboxSize.increment()
      }
      super.captureEnvelopeContext()
    }

    def processMessageStart(envelopeContext: TimestampedContinuation): TimestampedActiveSpan =
      TimestampedActiveSpan(System.nanoTime(), envelopeContext.continuation.activate())

    def processMessageEnd(timestampedActiveSpan: TimestampedActiveSpan, envelopeContext: TimestampedContinuation): Unit = {
      val activeSpan = timestampedActiveSpan.activeSpan
      val timestampBeforeProcessing = timestampedActiveSpan.nanoTime

      try activeSpan.deactivate() finally {
        val timestampAfterProcessing = System.nanoTime()
        val timeInMailbox = timestampBeforeProcessing - envelopeContext.nanoTime
        val processingTime = timestampAfterProcessing - timestampBeforeProcessing

        actorMetrics.foreach { am =>
          am.processingTime.record(processingTime)
          am.timeInMailbox.record(timeInMailbox)
          am.mailboxSize.decrement()
        }
        recordProcessMetrics(processingTime, timeInMailbox)
      }
    }

    override def processFailure(failure: Throwable): Unit = {
      actorMetrics.foreach { am =>
        am.errors.increment()
      }
      super.processFailure(failure: Throwable)
    }

    override def cleanup(): Unit = {
      super.cleanup()
      actorMetrics.foreach(_.cleanup())
    }
  }

  class TrackedRoutee(routerMetrics: RouterMetrics, groupMetrics: Seq[ActorGroupMetrics], actorCellCreation: Boolean)
    extends GroupMetricsTrackingActor(groupMetrics, actorCellCreation) {

    def processMessageStart(envelopeContext: TimestampedContinuation): TimestampedActiveSpan =
      TimestampedActiveSpan(System.nanoTime(), envelopeContext.continuation.activate())

    def processMessageEnd(timestampedActiveSpan: TimestampedActiveSpan, envelopeContext: TimestampedContinuation): Unit = {
      val activeSpan = timestampedActiveSpan.activeSpan
      val timestampBeforeProcessing = timestampedActiveSpan.nanoTime

      try activeSpan.deactivate() finally {
        val timestampAfterProcessing = System.nanoTime()
        val timeInMailbox = timestampBeforeProcessing - envelopeContext.nanoTime
        val processingTime = timestampAfterProcessing - timestampBeforeProcessing

        routerMetrics.processingTime.record(processingTime)
        routerMetrics.timeInMailbox.record(timeInMailbox)
        recordProcessMetrics(processingTime, timeInMailbox)
      }
    }

    override def processFailure(failure: Throwable): Unit = {
      routerMetrics.errors.increment()
      super.processFailure(failure)
    }

    override def cleanup(): Unit = {
      super.cleanup()
      routerMetrics.cleanup()
    }
  }

  abstract class GroupMetricsTrackingActor(groupMetrics: Seq[ActorGroupMetrics], actorCellCreation: Boolean) extends ActorMonitor {
    if (actorCellCreation) {
      groupMetrics.foreach { gm =>
        gm.members.increment()
      }
    }

    def captureEnvelopeContext(): TimestampedContinuation = {
      groupMetrics.foreach { gm =>
        gm.mailboxSize.increment()
      }

      TimestampedContinuation(System.nanoTime(), Kamon.activeSpanContinuation())
    }

    def processFailure(failure: Throwable): Unit = {
      groupMetrics.foreach { gm =>
        gm.errors.increment()
      }
    }

    protected def recordProcessMetrics(processingTime: Long, timeInMailbox: Long): Unit = {
      groupMetrics.foreach { gm =>
        gm.processingTime.record(processingTime)
        gm.timeInMailbox.record(timeInMailbox)
        gm.mailboxSize.decrement()
      }
    }

    def cleanup(): Unit = {
      if (actorCellCreation) {
        groupMetrics.foreach { gm =>
          gm.members.decrement()
        }
      }
    }
  }
}