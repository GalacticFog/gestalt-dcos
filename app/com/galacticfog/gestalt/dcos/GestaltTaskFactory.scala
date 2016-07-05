package com.galacticfog.gestalt.dcos

import java.util.UUID

import org.apache.mesos.Protos
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._

case class AppSpec(name: String,
                   env: Map[String,String],
                   image: String,
                   network: Protos.ContainerInfo.DockerInfo.Network,
                   cpu: Double,
                   mem: Double)


class GestaltTaskFactory {
  import GestaltTaskFactory._

  val allTasks = Seq("security")

  def getAppSpec(name: String): AppSpec = {
    name match {
      case "security" => getSecurity
      case "meta" => getMeta
    }
  }

  def complete(builder: TaskInfo.Builder, offer: Offer) = {
    builder
      .setSlaveId(offer.getSlaveId)
      .setTaskId(
        Protos.TaskID.newBuilder().setValue(builder.getName + "-" + UUID.randomUUID.toString)
      )
      .build()
  }

  private[this] def getSecurity: AppSpec = AppSpec(
    name = "security",
    env = Map(
      "DATABASE_HOSTNAME" -> "10.99.99.10",
      "DATABASE_PORT" -> "5432",
      "DATABASE_NAME" -> "gestalt-security",
      "DATABASE_USERNAME" -> "gestalt-dev",
      "DATABASE_PASSWORD" -> "letmein",
      "OAUTH_RATE_LIMITING_AMOUNT" -> "100",
      "OAUTH_RATE_LIMITING_PERIOD" -> "1"
    ),
    image = "galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a",
    network = ContainerInfo.DockerInfo.Network.BRIDGE,
    cpu = 0.25,
    mem = 512
  )

  private[this] def getMeta: AppSpec = ???

  implicit private[this] def getVariables(env: Map[String,String]): Environment = {
    val builder = Environment.newBuilder()
    env.foreach {
      case (name,value) => builder.addVariables(Variable.newBuilder
        .setName(name)
        .setValue(value)
      )
    }
    builder.build
  }

  def toTaskInfo(app: AppSpec, offer: Offer): TaskInfo = {
    val commandInfo = CommandInfo.newBuilder()
      .setShell(false)
      .setEnvironment(app.env)

    val containerInfo = Protos.ContainerInfo.newBuilder
      .setType( Protos.ContainerInfo.Type.DOCKER )
      .setDocker( ContainerInfo.DockerInfo.newBuilder
        .setImage(app.image)
        .setForcePullImage(true)
        .setNetwork(app.network)
        .build
      )

    TaskInfo.newBuilder()
      .setName( app.name )
      .addResources(
        Resource.newBuilder()
          .setName("cpus")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue( app.cpu ))
      )
      .setLabels(Protos.Labels.newBuilder().addLabels(
        Protos.Label.newBuilder().setKey(TASK_NAME_KEY).setValue(app.name).build
      ).build)
      .addResources(
        Resource.newBuilder()
          .setName("mem")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue( app.mem ))
      )
      .setCommand(commandInfo)
      .setContainer(containerInfo)
      .setSlaveId(offer.getSlaveId)
      .setTaskId(
        Protos.TaskID.newBuilder().setValue(app.name + "-" + UUID.randomUUID.toString)
      )
      .build()
  }

}

object GestaltTaskFactory {
  val TASK_NAME_KEY = "gestalt-service-name"

}
