package akka.kamon.instrumentation

import akka.dispatch.Envelope
import kamon.context.Context
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{Around, Aspect, DeclareMixin}

case class TimestampedContext(nanoTime: Long, @transient context: Context)

trait InstrumentedEnvelope extends Serializable {
  def timestampedContext(): TimestampedContext
  def setTimestampedContext(timestampedContext: TimestampedContext): Unit
}

object InstrumentedEnvelope {
  def apply(): InstrumentedEnvelope = new InstrumentedEnvelope {
    var timestampedContext: TimestampedContext = _

    def setTimestampedContext(timestampedContext: TimestampedContext): Unit =
      this.timestampedContext = timestampedContext
  }
}
