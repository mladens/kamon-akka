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

import akka.actor.Cell

import kamon.akka.Metrics.RouterMetrics
import kamon.akka.Metrics

trait RouterMonitor {
  def processMessageStart(): Long
  def processMessageEnd(timestampBeforeProcessing: Long): Unit
  def processFailure(failure: Throwable): Unit
  def cleanup(): Unit

  def routeeAdded(): Unit
  def routeeRemoved(): Unit
}

object RouterMonitor {

  def createRouterInstrumentation(cell: Cell): RouterMonitor = {
    val cellInfo = CellInfo.cellInfoFor(cell, cell.system, cell.self, cell.parent, false)

    if (cellInfo.isTracked)
      new MetricsOnlyRouterMonitor(Metrics.forRouter(cellInfo.path))
    else NoOpRouterMonitor
  }
}

object NoOpRouterMonitor extends RouterMonitor {
  def processMessageStart(): Long = 0L
  def processMessageEnd(timestampBeforeProcessing: Long): Unit = {}
  def processFailure(failure: Throwable): Unit = {}
  def routeeAdded(): Unit = {}
  def routeeRemoved(): Unit = {}
  def cleanup(): Unit = {}
}

class MetricsOnlyRouterMonitor(routerMetrics: RouterMetrics) extends RouterMonitor {

  def processMessageStart(): Long = System.nanoTime()

  def processMessageEnd(timestampBeforeProcessing: Long): Unit = {
    val timestampAfterProcessing = System.nanoTime()
    val routingTime = timestampAfterProcessing - timestampBeforeProcessing

    routerMetrics.routingTime.record(routingTime)
  }

  def processFailure(failure: Throwable): Unit = {}
  def routeeAdded(): Unit = {}
  def routeeRemoved(): Unit = {}
  def cleanup(): Unit = routerMetrics.cleanup()
}