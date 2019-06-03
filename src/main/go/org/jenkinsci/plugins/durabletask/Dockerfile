FROM golang:1.12.5
WORKDIR /durable-task-plugin/src/main/go/org/jenkinsci/plugins/durabletask
ARG nameprefix=heartbeat-launcher
RUN go get -d -v golang.org/x/sys/unix  
COPY heartbeat-launcher.go .
RUN CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -a -o ${nameprefix}-DARWIN
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -a -o ${nameprefix}-UNIX