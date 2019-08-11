#! /bin/sh
# Convenience script to rebuild golang binaries during development
set -x
# maven plugin version
VER=$1
TOP_LEVEL=$(git rev-parse --show-toplevel)
NAME="durable_task_monitor"
RESFOLDER="${TOP_LEVEL}/src/main/resources/org/jenkinsci/plugins/durabletask"
rm -rf ${RESFOLDER}/${NAME}_*
env CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -a -o ${NAME}_${VER}_darwin_64
env CGO_ENABLED=0 GOOS=darwin GOARCH=386 go build -a -o ${NAME}_${VER}_darwin_32
env CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -a -o ${NAME}_${VER}_unix_64
env CGO_ENABLED=0 GOOS=linux GOARCH=386 go build -a -o ${NAME}_${VER}_unix_32
mv ${NAME}_* ${RESFOLDER}/.
