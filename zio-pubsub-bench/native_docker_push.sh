#!/usr/bin/env bash
set -e

SCALANATIVE_MODE=release-fast ./mill testHttpNative.nativeLink

rm -rf docker_native/bin && mkdir docker_native/bin
cp out/testHttpNative/nativeLink.dest/out docker_native/bin/zio_pubsub_test

cd docker_native && docker build -t asia-docker.pkg.dev/anychat-staging/docker/zio_pubsub_test_http_native:latest .
docker push asia-docker.pkg.dev/anychat-staging/docker/zio_pubsub_test_http_native:latest