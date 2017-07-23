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
export GESTALT_API_GATEWAY_IMG="gateway:override"
export GESTALT_API_PROXY_IMG="proxy:override"
export GESTALT_UI_IMG="ui:override"
export LASER_EXECUTOR_JS_IMG="js:override"
export LASER_EXECUTOR_JVM_IMG="jvm:override"
export LASER_EXECUTOR_DOTNET_IMG="dotnet:override"
export LASER_EXECUTOR_PYTHON_IMG="python:override"
export LASER_EXECUTOR_RUBY_IMG="ruby:override"
export LASER_EXECUTOR_GOLANG_IMG="golang:override"

sbt 'testOnly TaskFactoryEnvSpec'

export DCOS_AUTH_METHOD=acs
export DCOS_ACS_SERVICE_ACCT_CREDS='{"uid":"service-account-id","login_endpoint":"https://leader.mesos/acs/api/v1/auth/login","scheme":"RS256","private_key":"-----BEGIN RSA PRIVATE KEY-----\nMIIEpAI..."}'
export LASER_ADVERTISE_HOSTNAME=laser.marathon.mesos
export MARATHON_NETWORK_NAME=user-network-1
export MESOS_HEALTH_CHECKS=true

sbt 'testOnly TaskFactoryEnvSpec'

