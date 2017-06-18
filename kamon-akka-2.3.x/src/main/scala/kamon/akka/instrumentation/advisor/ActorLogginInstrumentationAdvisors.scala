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

package kamon.akka.instrumentation.advisor

import java.util

import akka.event.Logging.LogEvent
import io.opentracing.ActiveSpan
import io.opentracing.NoopActiveSpanSource.NoopActiveSpan
import kamon.agent.libs.net.bytebuddy.asm.Advice.{Argument, Enter, OnMethodEnter, OnMethodExit}
import kamon.util.{HasContinuation, HexCodec}
import org.slf4j.MDC

/**
  * Advisor for akka.event.slf4j.Slf4jLogger::withMdc
  */
class BaggageOnMDCAdvisor
object BaggageOnMDCAdvisor {
  import kamon.trace.{SpanContext => KamonSpanContext}
  import scala.collection.JavaConverters._

  val TraceIDKey = "trace_id"

  @OnMethodEnter
  def onEnter(@Argument(1) logEvent: LogEvent): (ActiveSpan, Iterable[util.Map.Entry[String, String]]) = {
    val activeSpan = Option(logEvent.asInstanceOf[HasContinuation].continuation).map(_.activate()).getOrElse(NoopActiveSpan.INSTANCE)

    val baggageItems = activeSpan.context().baggageItems().asScala
    baggageItems.foreach(entry => MDC.put(entry.getKey, entry.getValue))
    addTraceIDToMDC(activeSpan.context())

    (activeSpan, baggageItems)
  }

  @OnMethodExit
  def onExit(@Enter spanWithBaggage:(ActiveSpan, Iterable[util.Map.Entry[String, String]])): Unit = {
    val (activeSpan,baggageItems) = spanWithBaggage
    baggageItems.foreach(entry => MDC.remove(entry.getKey))
    MDC.remove(TraceIDKey)
    activeSpan.deactivate()
  }

  private def addTraceIDToMDC(context: io.opentracing.SpanContext): Unit = context match {
    case ctx: KamonSpanContext =>  MDC.put(TraceIDKey, HexCodec.toLowerHex(ctx.traceID))
    case _ =>
  }
}