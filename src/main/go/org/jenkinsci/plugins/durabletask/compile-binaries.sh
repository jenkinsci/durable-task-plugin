#! /bin/bash
set -x
NAME="heartbeat-launcher"
rm -rf ${NAME}-*
env CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -a -o ${NAME}-DARWIN64
env CGO_ENABLED=0 GOOS=darwin GOARCH=386 go build -a -o ${NAME}-DARWIN32
env CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -a -o ${NAME}-UNIX64
env CGO_ENABLED=0 GOOS=linux GOARCH=386 go build -a -o ${NAME}-UNIX32
