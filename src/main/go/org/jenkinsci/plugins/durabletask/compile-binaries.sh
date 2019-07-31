#! /bin/sh
set -x
NAME="durable_task_monitor"
rm -rf ${NAME}_*
env CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -a -o ${NAME}_DARWIN64
env CGO_ENABLED=0 GOOS=darwin GOARCH=386 go build -a -o ${NAME}_DARWIN32
env CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -a -o ${NAME}_UNIX64
env CGO_ENABLED=0 GOOS=linux GOARCH=386 go build -a -o ${NAME}_UNIX32
