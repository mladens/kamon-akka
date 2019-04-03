package akka.kamon.instrumentation


import akka.actor.{ActorSystem, DeadLetter, UnhandledMessage}
import kamon.akka.Metrics
import org.aspectj.lang.annotation.{After, Aspect, DeclareMixin, Pointcut}

trait HasSystem {
  def system: ActorSystem
  def setSystem(system: ActorSystem): Unit
}

object HasSystem {
  def apply(): HasSystem = new HasSystem {
    private var _system: ActorSystem = _

    override def system: ActorSystem = _system

    override def setSystem(system: ActorSystem): Unit = _system = system
  }
}
