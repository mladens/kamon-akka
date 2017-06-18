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

import kamon.agent.scala.KamonInstrumentation
import kamon.akka.instrumentation.advisor.BaggageOnMDCAdvisor
import kamon.instrumentation.mixin.HasContinuationMixin

class ActorLoggingInstrumentation extends KamonInstrumentation {

  /**
    * Mix:
    *
    *  akka.event.Logging$LogEvent with kamon.instrumentation.mixin.HasContinuationMixin
    *
    */
  forSubtypeOf("akka.event.Logging$LogEvent") { builder ⇒
    builder
      .withMixin(classOf[HasContinuationMixin])
      .build()
  }

  /**
    * Instrument:
    *
    *  akka.event.slf4j.Slf4jLogger::withMdc
    *
    */
  forTargetType("akka.event.slf4j.Slf4jLogger") { builder ⇒
    builder
      .withAdvisorFor(named("withMdc"), classOf[BaggageOnMDCAdvisor])
      .build()
  }
}

