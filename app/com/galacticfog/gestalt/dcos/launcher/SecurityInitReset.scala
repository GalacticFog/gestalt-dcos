package com.galacticfog.gestalt.dcos.launcher

import akka.event.LoggingAdapter
import com.galacticfog.gestalt.dcos.GlobalDBConfig

import scala.util.Try

case class SecurityInitReset(dbConfig: GlobalDBConfig) {

  import scalikejdbc._

  def clearInit()(implicit log: LoggingAdapter): Unit = {
    val driver = "org.postgresql.Driver"
    val url = "jdbc:postgresql://%s:%d/%s".format(dbConfig.hostname, dbConfig.port, dbConfig.prefix + "security")
    log.info("initializing connection pool against " + url)

    Class.forName(driver)

    val settings = ConnectionPoolSettings(
      connectionTimeoutMillis = 5000
    )

    ConnectionPool.singleton(url, dbConfig.username, dbConfig.password, settings)
    println("ConnectionPool.isInitialized: " + ConnectionPool.isInitialized())

    implicit val session = AutoSession
    Try {
      sql"update initialization_settings set initialized = false where id = 0".execute.apply()
      ConnectionPool.closeAll()
    } recover {
      case e: Throwable =>
        log.warning(s"error clearing init flag on ${dbConfig.prefix}security database: {}", e.getMessage)
        false
    }

    ConnectionPool.closeAll()
  }

}
