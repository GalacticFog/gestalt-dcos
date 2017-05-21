package com.galacticfog.gestalt.dcos

object GlobalConfig {
  def empty: GlobalConfig = GlobalConfig(None,None)
  def apply(): GlobalConfig = GlobalConfig.empty
}

case class GlobalConfig( dbConfig: Option[GlobalDBConfig],
                         secConfig: Option[GlobalSecConfig] ) {
  def withDb(dbConfig: GlobalDBConfig): GlobalConfig = this.copy(
    dbConfig = Some(dbConfig)
  )

  def withSec(secConfig: GlobalSecConfig): GlobalConfig = this.copy(
    secConfig = Some(secConfig)
  )
}

case class GlobalDBConfig( hostname: String,
                           port: Int,
                           username: String,
                           password: String,
                           prefix: String )

case class GlobalSecConfig( hostname: String,
                            port: Int,
                            apiKey: String,
                            apiSecret: String,
                            realm: String )
