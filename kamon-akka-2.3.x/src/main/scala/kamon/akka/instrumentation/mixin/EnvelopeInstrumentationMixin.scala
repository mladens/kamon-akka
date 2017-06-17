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

package kamon.akka.instrumentation.mixin

import io.opentracing.ActiveSpan
import io.opentracing.ActiveSpan.Continuation
import io.opentracing.NoopActiveSpanSource.{NoopActiveSpan, NoopContinuation}

/**
  * Mixin for akka.dispatch.Envelope
  */
class EnvelopeInstrumentationMixin extends InstrumentedEnvelope {
  @volatile var timestampedContinuation: TimestampedContinuation = _

  def setTimestampedContinuation(envelopeContext: TimestampedContinuation): Unit = {
    this.timestampedContinuation = envelopeContext
  }
}

case class TimestampedContinuation(nanoTime: Long, continuation: Continuation)
case class TimestampedActiveSpan(nanoTime: Long, activeSpan: ActiveSpan)

object TimestampedContinuation {
  val Empty = TimestampedContinuation(0L, NoopContinuation.INSTANCE)
}

object TimestampedActiveSpan {
  val Empty = TimestampedActiveSpan(0L, NoopActiveSpan.INSTANCE)
}

trait InstrumentedEnvelope {
  def timestampedContinuation(): TimestampedContinuation
  def setTimestampedContinuation(envelopeContext: TimestampedContinuation): Unit
}