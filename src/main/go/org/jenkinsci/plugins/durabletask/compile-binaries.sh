#! /bin/sh
set -x
NAME="durable_task_monitor"
rm -rf ${NAME}_*
env CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -a -o ${NAME}_darwin_64
env CGO_ENABLED=0 GOOS=darwin GOARCH=386 go build -a -o ${NAME}_darwin_32
env CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -a -o ${NAME}_unix_64
env CGO_ENABLED=0 GOOS=linux GOARCH=386 go build -a -o ${NAME}_unix_32
