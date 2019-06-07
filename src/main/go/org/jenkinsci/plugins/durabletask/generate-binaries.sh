#! /bin/sh
set -x
# maven plugin version
VER=$1
# path to the golang sournce
SRC=$2
# path to where the binaries should be moved to
DST=$3
IMG_NAME="hbl-builder"
BIN_NAME="heartbeat-launcher"
docker build -t ${IMG_NAME}:${VER} .
docker run -i --rm \
    --mount type=bind,src=${SRC},dst=/org/jenkinsci/plugins/durabletask \
    ${IMG_NAME}:${VER}
mkdir -p ${DST}
mv ${BIN_NAME}-* ${DST}/
docker rmi ${IMG_NAME}:${VER}