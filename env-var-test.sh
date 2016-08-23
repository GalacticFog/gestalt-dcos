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
export GESTALT_LAMBDA_IMG="lambda:override"
export GESTALT_API_GATEWAY_IMG="gateway:override"
export GESTALT_API_PROXY_IMG="proxy:override"
export GESTALT_UI_IMG="ui:override"
export LAMBDA_JAVASCRIPT_EXECUTOR_IMG="lambda-js:override"
export LAMBDA_JAVA_EXECUTOR_IMG="lambda-java:override"
export LAMBDA_DOTNET_EXECUTOR_IMG="lambda-dotnet:override"

sbt 'testOnly TaskFactoryEnvSpec'

