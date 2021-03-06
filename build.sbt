name := """gestalt-dcos"""

lazy val root = (project in file(".")).
  enablePlugins(PlayScala, BuildInfoPlugin, GitVersioning).
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
          } catch {      case _: Throwable => "get git hash failed"    }
        }}.toString()
    ),
    buildInfoPackage := "com.galacticfog.gestalt.dcos",
    buildInfoUsePackageAsPath := true
  )

git.baseVersion := "2.2.0"
git.useGitDescribe := true

scalaVersion := "2.11.11"

javaOptions in Test += "-Dlogger.file=conf/debug-logging.xml"

import com.typesafe.sbt.packager.docker._
maintainer in Docker := "Chris Baker <chris@galacticfog.com>"
packageName in Docker := "galacticfog/gestalt-dcos"
dockerBaseImage := "java:8-jre-alpine"
dockerExposedPorts := Seq(9000)
dockerCommands := dockerCommands.value.flatMap {
  case cmd@Cmd("FROM",_) => List(
    cmd,
    Cmd("RUN", "apk add --update bash && rm -rf /var/cache/apk/*")     
  )
  case other => List(other)
}
dockerLabels := Map(
  "org.label-schema.schema-version" -> "1.0",
  "org.label-schema.vcs-ref" -> git.gitHeadCommit.value.getOrElse(""),
  "org.label-schema.vcs-url" -> "https://github.com/GalacticFog/gestalt-dcos",
  "org.label-schema.name" -> "galacticfog/gestalt-dcos",
  "org.label-schema.description" -> "Galactic Fog Installer for Mesosphere DC/OS"
)

resolvers ++= Seq("snapshots", "releases").map(Resolver.sonatypeRepo)

resolvers ++= Seq(
  "gestalt-snapshots" at "https://galacticfog.artifactoryonline.com/galacticfog/libs-snapshots-local",
  "gestalt-releases" at "https://galacticfog.artifactoryonline.com/galacticfog/libs-releases-local"
)

libraryDependencies ++= Seq(
  "com.galacticfog" %% "gestalt-security-sdk-scala" % "2.4.0-SNAPSHOT" withSources(),
  "com.galacticfog" %% "gestalt-cli" % "2.4.2" withSources()
)

scalacOptions ++= Seq("-feature")

scalacOptions in Test ++= Seq("-Yrangepos")

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
libraryDependencies ++= Seq(
  jdbc,
  ws,
  guice,
  "com.typesafe.akka" %% "akka-http" % "10.0.8",
  "com.typesafe.play" %% "play-json" % "2.6.9",
  //
  "org.webjars" %% "webjars-play" % "2.6.1",
  "org.webjars" % "bootstrap" % "3.1.1-2",
  //
  "org.scalikejdbc" %% "scalikejdbc" % "3.2.2",
  "org.postgresql" % "postgresql" % "9.3-1104-jdbc4",
  "net.codingwell"  %% "scala-guice" 					 % "4.1.1",
  specs2 % Test,
  "com.typesafe.akka" %% "akka-testkit" % "2.5.11" % Test,
  "de.leanovate.play-mockws" %% "play-mockws" % "2.6.2" % Test,
  "org.specs2" %% "specs2-matcher-extra" % "3.8.9" % "test"
)
