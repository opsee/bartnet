#!/usr/bin/env bash

PG_CONTAINER_NAME=""

while getopts p: opt; do
  case $opt in
    p)
      PG_CONTAINER_NAME=$OPTARG
      ;;
    *)
      usage
      ;;
  esac
done

usage() {
  cat <<EOF
usage: $0 [-p]

  -p POSTGRES_CONTAINER_NAME (postgres)
EOF
}

DOCKER_OPTS="--rm --name bartnet -p 4080:4080 -p 8080:8080"

if [ -n "$PG_CONTAINER_NAME" ]; then
  DOCKER_OPTS="--link $PG_CONTAINER_NAME:$PG_CONTAINER_NAME $DOCKER_OPTS" 
fi 

if [ -n "$AWS_ACCESS_KEY_ID" ]; then
  DOCKER_OPTS="$DOCKER_OPTS -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY"
fi

set -x
docker run $DOCKER_OPTS opsee/bartnet
