#!/bin/bash

set -e

sbt test

export GESTALT_FRAMEWORK_VERSION="9.10.11.12"

sbt 'testOnly TaskFactoryEnvSpec'

export GESTALT_DATA_IMG="data:override"

sbt 'testOnly TaskFactoryEnvSpec'

export GESTALT_RABBIT_IMG="rabbit:override"
export GESTALT_KONG_IMG="kong:override"
export GESTALT_SECURITY_IMG="security:override"
export GESTALT_META_IMG="meta:override"
export GESTALT_POLICY_IMG="policy:override"
export GESTALT_LASER_IMG="lambda:override"
export GESTALT_LOG_IMG="log:override"
export GESTALT_API_GATEWAY_IMG="gateway:override"
export GESTALT_API_PROXY_IMG="proxy:override"
export GESTALT_UI_IMG="ui:override"
export LASER_EXECUTOR_NODEJS_IMG="nodejs:override"
export LASER_EXECUTOR_JS_IMG="js:override"
export LASER_EXECUTOR_JVM_IMG="jvm:override"
export LASER_EXECUTOR_DOTNET_IMG="dotnet:override"
export LASER_EXECUTOR_PYTHON_IMG="python:override"
export LASER_EXECUTOR_RUBY_IMG="ruby:override"
export LASER_EXECUTOR_GOLANG_IMG="golang:override"

sbt 'testOnly TaskFactoryEnvSpec'

export DCOS_AUTH_METHOD=acs
export DCOS_ACS_SERVICE_ACCT_CREDS='{"uid":"service-account-id","login_endpoint":"https://leader.mesos/acs/api/v1/auth/login","scheme":"RS256","private_key":"-----BEGIN RSA PRIVATE KEY-----\nMIIEpAI..."}'
export MARATHON_NETWORK_NAME=user-network-1
export MESOS_HEALTH_CHECKS=true
export MARATHON_NETWORK_LIST="user-network-1,user-network-2"
export MARATHON_HAPROXY_GROUPS="custom-haproxy-1,custom-haproxy-2"
export MARATHON_LB_URL="marathon-lb.myco.com"
export MARATHON_TLD="myco.com"

export LASER_ADVERTISE_HOSTNAME=laser.marathon.mesos
export LASER_MAX_CONN_TIME=45
export LASER_EXECUTOR_HEARTBEAT_TIMEOUT=15000
export LASER_EXECUTOR_HEARTBEAT_PERIOD=30000

export DATABASE_USERNAME=dbuser
export DATABASE_PASSWORD=dbpass
export DATABASE_PROVISION=true
export DATABASE_HOSTNAME=dbhost
export DATABASE_NUM_SECONDARIES=10
export DATABASE_PGREPL_TOKEN=sharingisgood
export DATABASE_PORT=5555
export DATABASE_PREFIX=dbprefix-
export DATABASE_PROVISIONED_CPU=5.0
export DATABASE_PROVISIONED_MEMORY=8192
export DATABASE_PROVISIONED_SIZE=1000
export LOGGING_ES_HOST=my-elastic-cluster.com
export LOGGING_ES_PORT_REST=9200
export LOGGING_ES_PORT_TRANSPORT=9300
export LOGGING_ES_CLUSTER_NAME=my-es-cluster
export LOGGING_ES_PROTOCOL=https
export LOGGING_CONFIGURE_LASER=true
export LOGGING_PROVISION_PROVIDER=true

sbt 'testOnly TaskFactoryEnvSpec'

