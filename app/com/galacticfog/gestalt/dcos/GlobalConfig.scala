package com.galacticfog.gestalt.dcos

object GlobalConfig {
  def empty: GlobalConfig = GlobalConfig(None,None,None)
  def apply(): GlobalConfig = GlobalConfig.empty
}

case class GlobalConfig( dbConfig: Option[GlobalDBConfig],
                         secConfig: Option[GlobalSecConfig],
                         elasticConfig: Option[GlobalElasticConfig]) {
  def withDb(dbConfig: GlobalDBConfig): GlobalConfig = this.copy(
    dbConfig = Some(dbConfig)
  )

  def withSec(secConfig: GlobalSecConfig): GlobalConfig = this.copy(
    secConfig = Some(secConfig)
  )

  def withElastic(elasticConfig: Option[GlobalElasticConfig]): GlobalConfig = this.copy(
    elasticConfig = elasticConfig
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
                            realm: Option[String] )

case class GlobalElasticConfig( hostname: String,
                                protocol: String,
                                portApi: Int,
                                portSvc: Int,
                                clusterName: String )
