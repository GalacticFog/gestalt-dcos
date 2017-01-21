name := """gestalt-dcos"""

version := "0.3.0-RC1"

lazy val root = (project in file(".")).
  enablePlugins(PlayScala).
  enablePlugins(BuildInfoPlugin).
  settings(
    buildInfoKeys := Seq[BuildInfoKey](
      name, version, scalaVersion, sbtVersion,
      "builtBy" -> System.getProperty("user.name"),
      "gitHash" -> new java.lang.Object(){
              override def toString(): String = {
                      try { 
                    val extracted = new java.io.InputStreamReader(
                              java.lang.Runtime.getRuntime().exec("git rev-parse HEAD").getInputStream())                         
                    (new java.io.BufferedReader(extracted)).readLine()
                      } catch {      case t: Throwable => "get git hash failed"    }
              }}.toString()
    ),
    buildInfoPackage := "com.galacticfog.gestalt.dcos",
    buildInfoUsePackageAsPath := true
  )

scalaVersion := "2.11.7"

import com.typesafe.sbt.packager.docker._
maintainer in Docker := "Chris Baker <chris@galacticfog.com>"
dockerUpdateLatest := true
dockerBaseImage := "galacticfog/gestalt-mesos-base:3.1.0"
dockerExposedPorts := Seq(9000)

resolvers ++= Seq("snapshots", "releases").map(Resolver.sonatypeRepo)

resolvers += "Mesosphere Repo" at "http://downloads.mesosphere.io/maven"
libraryDependencies += "mesosphere" %% "mesos-utils" % "0.28.0" withJavadoc()

resolvers += "gestalt" at "http://galacticfog.artifactoryonline.com/galacticfog/libs-snapshots-local"
libraryDependencies += "com.galacticfog" %% "gestalt-security-sdk-scala" % "2.4.0-SNAPSHOT" withSources()

resolvers += Resolver.bintrayRepo("hseeberger", "maven")
libraryDependencies += "de.heikoseeberger" %% "akka-sse" % "2.0.0"

scalacOptions ++= Seq("-feature")

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  specs2 % Test,
  "org.webjars" %% "webjars-play" % "2.5.0",
  "org.webjars" % "bootstrap" % "3.1.1-2",
  "org.scalikejdbc" %% "scalikejdbc" % "2.4.2",
  "org.postgresql" % "postgresql" % "9.3-1102-jdbc4"
)

libraryDependencies ++= Seq("org.specs2" %% "specs2-matcher-extra" % "3.6.6" % "test")
