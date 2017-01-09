name := """gestalt-dcos"""

version := "0.2.4-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

import com.typesafe.sbt.packager.docker._
dockerUpdateLatest := false
dockerRepository := Some("galacticfog.artifactoryonline.com")
dockerBaseImage := "galacticfog.artifactoryonline.com/gestalt-mesos-base:v1"

resolvers ++= Seq("snapshots", "releases").map(Resolver.sonatypeRepo)
libraryDependencies ++= Seq("org.specs2" %% "specs2-core" % "3.8.4" % "test")
libraryDependencies ++= Seq("org.specs2" %% "specs2-matcher-extra" % "3.8.4" % "test")

resolvers += "Mesosphere Repo" at "http://downloads.mesosphere.io/maven"
libraryDependencies += "mesosphere" %% "mesos-utils" % "0.28.0" withJavadoc()

resolvers += "gestalt" at "http://galacticfog.artifactoryonline.com/galacticfog/libs-snapshots-local"
libraryDependencies += "com.galacticfog" %% "gestalt-security-sdk-scala" % "2.2.5-SNAPSHOT" withSources()

resolvers += Resolver.bintrayRepo("hseeberger", "maven")
libraryDependencies += "de.heikoseeberger" %% "akka-sse" % "2.0.0"

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  "org.webjars" %% "webjars-play" % "2.5.0",
  "org.webjars" % "bootstrap" % "3.1.1-2",
  "org.scalikejdbc" %% "scalikejdbc" % "2.4.2",
  "org.postgresql" % "postgresql" % "9.3-1102-jdbc4"
)
