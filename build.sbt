name := """gestalt-dcos"""

version := "0.1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

import com.typesafe.sbt.packager.docker._
dockerUpdateLatest := false
dockerRepository := Some("galacticfog.artifactoryonline.com")
dockerBaseImage := "galacticfog.artifactoryonline.com/gestalt-mesos-base:v1"

resolvers ++= Seq("snapshots", "releases").map(Resolver.sonatypeRepo)
resolvers += "Mesosphere Repo" at "http://downloads.mesosphere.io/maven"

libraryDependencies += "mesosphere" %% "mesos-utils" % "0.28.0" withJavadoc()
libraryDependencies ++= Seq("org.specs2" %% "specs2-core" % "3.8.4" % "test")
libraryDependencies ++= Seq("org.specs2" %% "specs2-matcher-extra" % "3.8.4" % "test")

scalacOptions in Test ++= Seq("-Yrangepos")

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
