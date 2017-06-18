
/* =========================================================================================
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


val `akka-2.3`            = "2.3.13"
val `akka-2.4`            = "2.4.16"
val `akka-2.5`            = "2.5.2"
val `kamon-agent-version` = "0.0.3-experimental"

val kamonCore       = "io.kamon" %% "kamon-core"            % "1.0.0-RC1-dd645df1b7c462418c01074d0e137982d2f270b7"
val kamonScala      = "io.kamon" %% "kamon-scala"           % "1.0.0-RC1-b1dc4786bca305d87400cd423b347b4d689a9807"              exclude("io.kamon", "kamon-core")
val kamonExecutors  = "io.kamon" %% "kamon-executors"       % "1.0.0-RC1-24f29c9a3c52663ada75fd85edcc5fd3810e97a2"              exclude("io.kamon", "kamon-core")
val kamonScalaKa    = "io.kamon" %% "kamon-scala"           % "experimental-1.0.0-RC1-9a93b42263e5ef2519e217312e2a8bbb28e71323" exclude("io.kamon", "kamon-core")
val scalaExtension  = "io.kamon" %% "agent-scala-extension" % `kamon-agent-version`


lazy val `kamon-akka` = (project in file("."))
    .settings(noPublishing: _*)
    .aggregate(kamonAkka23, kamonAkka24, kamonAkka25)


lazy val kamonAkka23 = Project("kamon-akka-23", file("kamon-akka-2.3.x"))
  .settings(isSnapshot := true)
  .settings(Seq(
      bintrayPackage := "kamon-akka",
      moduleName := "kamon-akka-2.3",
      scalaVersion := "2.11.8",
      crossScalaVersions := Seq("2.10.6", "2.11.8")))
  .enablePlugins(JavaAgent)
  .settings(bintrayResolvers)
  .settings(agentSettings)
  .settings(
    libraryDependencies ++=
      compileScope(akkaDependency("actor", `akka-2.3`), kamonCore, kamonScalaKa, scalaExtension) ++
      optionalScope(logbackClassic) ++
      testScope(scalatest, akkaDependency("testkit", `akka-2.3`), akkaDependency("slf4j", `akka-2.3`), logbackClassic))


lazy val kamonAkka24 = Project("kamon-akka-24", file("kamon-akka-2.4.x"))
  .settings(isSnapshot := true)
  .settings(Seq(
      bintrayPackage := "kamon-akka",
      moduleName := "kamon-akka-2.4",
      scalaVersion := "2.12.1",
      crossScalaVersions := Seq("2.11.8", "2.12.1")))
  .enablePlugins(JavaAgent)
  .settings(bintrayResolvers)
  .settings(agentSettings)
  .settings(
    libraryDependencies ++=
      compileScope(akkaDependency("actor", `akka-2.4`), kamonCore, kamonScalaKa, scalaExtension) ++
      providedScope(aspectJ) ++
      optionalScope(logbackClassic) ++
      testScope(scalatest, akkaDependency("testkit", `akka-2.4`), akkaDependency("slf4j", `akka-2.4`), logbackClassic))

lazy val kamonAkka25 = Project("kamon-akka-25", file("kamon-akka-2.5.x"))
  .settings(isSnapshot := true)
  .settings(Seq(
     bintrayPackage := "kamon-akka",
     moduleName := "kamon-akka-2.5",
     scalaVersion := "2.12.2",
     crossScalaVersions := Seq("2.11.8", "2.12.2")))
  .enablePlugins(JavaAgent)
  .settings(bintrayResolvers)
  .settings(agentSettings)
  .settings(
     libraryDependencies ++=
       compileScope(akkaDependency("actor", `akka-2.5`), kamonCore, kamonScalaKa, scalaExtension) ++
       providedScope(aspectJ) ++
       optionalScope(logbackClassic) ++
       testScope(scalatest, akkaDependency("testkit", `akka-2.5`), akkaDependency("slf4j", `akka-2.5`), logbackClassic))

enableProperCrossScalaVersionTasks

lazy val agentSettings = Seq(javaAgents += "io.kamon"    % "kamon-agent"   % `kamon-agent-version`  % "compile;test")
lazy val bintrayResolvers = Seq(resolvers += Resolver.bintrayRepo("kamon-io", "snapshots"))

def akkaDependency(name: String, version: String) = {
  "com.typesafe.akka" %% s"akka-$name" % version
}
