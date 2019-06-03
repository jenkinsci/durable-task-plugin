FROM golang:1.12.5
WORKDIR /org/jenkinsci/plugins/durabletask
RUN go get -d -v golang.org/x/sys/unix
ENTRYPOINT [ "/bin/sh", "compile-binaries.sh" ]