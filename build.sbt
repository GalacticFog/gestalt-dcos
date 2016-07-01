name := """gestalt-dcos"""

version := "0.1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

import com.typesafe.sbt.packager.docker._
dockerUpdateLatest := false
dockerRepository := Some("galacticfog.artifactoryonline.com")

resolvers += Resolver.bintrayRepo("hseeberger", "maven")
resolvers ++= Seq("snapshots", "releases").map(Resolver.sonatypeRepo)

libraryDependencies ++= Seq("org.specs2" %% "specs2-core" % "3.8.4" % "test")
libraryDependencies ++= Seq("org.specs2" %% "specs2-matcher-extra" % "3.8.4" % "test")

scalacOptions in Test ++= Seq("-Yrangepos")

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  "de.heikoseeberger" %% "akka-sse" % "1.8.1"
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
