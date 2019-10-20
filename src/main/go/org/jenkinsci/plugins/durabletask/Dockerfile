FROM golang:1.12-stretch
ARG PLUGIN_VER
ENV VER=${PLUGIN_VER}
WORKDIR /org/jenkinsci/plugins/durabletask
RUN go get -d -v golang.org/x/sys/unix
ENTRYPOINT /bin/sh compile-binaries.sh $VER