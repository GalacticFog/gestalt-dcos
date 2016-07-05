package com.galacticfog.gestalt.dcos

import java.util.UUID

import org.apache.mesos.Protos
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._

class GestaltTaskFactory {

  val allTasks = Seq("security")

  def getTaskInfo(name: String): TaskInfo.Builder = {
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

  private[this] def getSecurity: TaskInfo.Builder = {

    val commandInfo = CommandInfo.newBuilder()
      .setShell(false)
      .setEnvironment(Map(
        "DATABASE_HOSTNAME" -> "10.99.99.10",
        "DATABASE_PORT" -> "5432",
        "DATABASE_NAME" -> "gestalt-security",
        "DATABASE_USERNAME" -> "gestalt-dev",
        "DATABASE_PASSWORD" -> "letmein",
        "OAUTH_RATE_LIMITING_AMOUNT" -> "100",
        "OAUTH_RATE_LIMITING_PERIOD" -> "1"
      ))

    val containerInfo = Protos.ContainerInfo.newBuilder
      .setType( Protos.ContainerInfo.Type.DOCKER )
      .setDocker(ContainerInfo.DockerInfo.newBuilder
        .setImage("galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a")
        .setForcePullImage(true)
        .setNetwork(ContainerInfo.DockerInfo.Network.BRIDGE)
        .build
      )

    TaskInfo.newBuilder()
      .setName("security")
      .addResources(
        Resource.newBuilder()
          .setName("cpus")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue(0.50))
      )
      .addResources(
        Resource.newBuilder()
          .setName("mem")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue(2048.0))
      )
      .setCommand(commandInfo)
      .setContainer(containerInfo)
  }

  private[this] def getMeta: TaskInfo.Builder = {
    ???
  }

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

}
