#!/bin/bash

set -e

sbt test

export GESTALT_FRAMEWORK_VERSION="9.10.11.12"

sbt 'testOnly TaskFactoryEnvSpec'

export GESTALT_DATA_IMG="data:override"

sbt 'testOnly TaskFactoryEnvSpec'

export GESTALT_RABBIT_IMG="rabbit:override"
export GESTALT_ELASTIC_IMG="elastic:override"
export GESTALT_KONG_IMG="kong:override"
export GESTALT_SECURITY_IMG="security:override"
export GESTALT_META_IMG="meta:override"
export GESTALT_POLICY_IMG="policy:override"
export GESTALT_LASER_IMG="lambda:override"
export GESTALT_LOG_IMG="log:override"
export GESTALT_API_GATEWAY_IMG="gateway:override"
export GESTALT_UI_IMG="ui:override"

export LASER_EXECUTOR_NODEJS_IMG="nodejs:override"
export LASER_EXECUTOR_JS_IMG="js:override"
export LASER_EXECUTOR_JVM_IMG="jvm:override"
export LASER_EXECUTOR_DOTNET_IMG="dotnet:override"
export LASER_EXECUTOR_PYTHON_IMG="python:override"
export LASER_EXECUTOR_RUBY_IMG="ruby:override"
export LASER_EXECUTOR_GOLANG_IMG="golang:override"
export LASER_EXECUTOR_BASH_IMG="bash:override"

sbt 'testOnly TaskFactoryEnvSpec'

# testing dcos stuff
export DCOS_AUTH_METHOD=acs
export DCOS_ACS_SERVICE_ACCT_CREDS='{"uid":"service-account-id","login_endpoint":"https://leader.mesos/acs/api/v1/auth/login","scheme":"RS256","private_key":"-----BEGIN RSA PRIVATE KEY-----\nMIIEpAI..."}'
export MARATHON_NETWORK_NAME=user-network-1
export MESOS_HEALTH_CHECKS=true
export MARATHON_NETWORK_LIST="user-network-1,user-network-2"
export MARATHON_HAPROXY_GROUPS="custom-haproxy-1,custom-haproxy-2"
export MARATHON_LB_URL="marathon-lb.myco.com"
export MARATHON_TLD="myco.com"

# testing well-known/expected laser env vars
export LASER_MAX_CONN_TIME=45
export LASER_SERVICE_HOST_OVERRIDE=laser-host-override
export LASER_SERVICE_PORT_OVERRIDE=11111
export LASER_EXECUTOR_HEARTBEAT_TIMEOUT=15000
export LASER_EXECUTOR_HEARTBEAT_PERIOD=30000
export LASER_SCALE_DOWN_TIMEOUT=60
# testing pass-through legacy laser env vars
export LASER_ETHERNET_PORT=eth7
export LASER_ADVERTISE_HOSTNAME=laser.marathon.mesos
export LASER_DEFAULT_EXECUTOR_CPU=2.0
export LASER_DEFAULT_EXECUTOR_RAM=4096
export LASER_MIN_COOL_EXECUTORS=5

# testing database override
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

# testing logging
export LOGGING_ES_HOST=my-elastic-cluster.com
export LOGGING_ES_PORT_REST=9200
export LOGGING_ES_PORT_TRANSPORT=9300
export LOGGING_ES_CLUSTER_NAME=my-es-cluster
export LOGGING_ES_PROTOCOL=https
export LOGGING_CONFIGURE_LASER=true
export LOGGING_PROVISION_PROVIDER=true

# testing extra env vars
export ELASTIC_EXTRA_VAR_U=u
export ELASTIC_EXTRA_VAR_20=20
export RABBIT_EXTRA_VAR_A=a
export RABBIT_EXTRA_VAR_1=1
export DATA_0_EXTRA_VAR_B=b
export DATA_0_EXTRA_VAR_2=2
export DATA_1_EXTRA_VAR_C=c
export DATA_1_EXTRA_VAR_3=3
export DATA_2_EXTRA_VAR_D=d
export DATA_2_EXTRA_VAR_4=4
export SECURITY_EXTRA_VAR_E=e
export SECURITY_EXTRA_VAR_5=5
export META_EXTRA_VAR_F=f
export META_EXTRA_VAR_6=6
export UI_EXTRA_VAR_G=g
export UI_EXTRA_VAR_7=7
export KONG_EXTRA_VAR_H=h
export KONG_EXTRA_VAR_8=8
export LASER_EXTRA_VAR_I=i
export LASER_EXTRA_VAR_9=9
export POLICY_EXTRA_VAR_J=j
export POLICY_EXTRA_VAR_10=10
export API_GATEWAY_EXTRA_VAR_K=k
export API_GATEWAY_EXTRA_VAR_11=11
export LOG_EXTRA_VAR_L=l
export LOG_EXTRA_VAR_12=12

export EXECUTOR_DOTNET_M=m
export EXECUTOR_DOTNET_13=13
export EXECUTOR_NASHORN_N=n
export EXECUTOR_NASHORN_14=14
export EXECUTOR_NODEJS_O=o
export EXECUTOR_NODEJS_15=15
export EXECUTOR_JVM_P=p
export EXECUTOR_JVM_16=16
export EXECUTOR_PYTHON_Q=q
export EXECUTOR_PYTHON_17=17
export EXECUTOR_GOLANG_R=r
export EXECUTOR_GOLANG_18=18
export EXECUTOR_RUBY_S=s
export EXECUTOR_RUBY_19=19
export EXECUTOR_BASH_T=t
export EXECUTOR_BASH_20=20

export CPU_API_GATEWAY=2.1
export CPU_KONG=2.2
export CPU_LASER=2.3
export CPU_LOG=2.4
export CPU_META=2.5
export CPU_POLICY=2.6
export CPU_RABBIT=2.7
export CPU_SECURITY=2.8
export CPU_UI=2.9
export MEM_API_GATEWAY=1031
export MEM_KONG=1032
export MEM_LASER=1033
export MEM_LOG=1034
export MEM_META=1035
export MEM_POLICY=1036
export MEM_RABBIT=1037
export MEM_SECURITY=1038
export MEM_UI=1039

sbt 'testOnly TaskFactoryEnvSpec'

