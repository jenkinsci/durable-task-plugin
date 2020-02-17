#! /bin/sh
set -x
# maven plugin version
VER=$1
# path to the golang source
SRC=$2
# path to where the binaries should be moved to
DST=$3
IMG_NAME="durable-task-binary-generator"
BIN_NAME="durable_task_monitor"
docker build --build-arg PLUGIN_VER=${VER} -t ${IMG_NAME}:${VER} .
docker run -i --rm \
    --mount type=bind,src=${SRC},dst=/org/jenkinsci/plugins/durabletask \
    ${IMG_NAME}:${VER}
docker rmi ${IMG_NAME}:${VER}
mkdir -p ${DST}
mv ${BIN_NAME}_* ${DST}/
if [ $? -ne 0 ]
then
  echo "Binary generation failed. To skip binary generation, set the environment variable 'SKIP_BINARY_GENERATION=true'"
  exit 1
fi