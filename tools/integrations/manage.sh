#!/usr/bin/env bash

set -e
set -x

. tools/lib/lib.sh


USAGE="
Usage: $(basename "$0") <cmd>
For publish, if you want to push the spec to the spec cache, provide a path to a service account key file that can write to the cache.
Available commands:
  publish  <integration_root_path> [<run_tests>] [--publish_spec_to_cache] [--publish_spec_to_cache_with_key_file <path to keyfile>] [--pre_release]
"

# these filenames must match DEFAULT_SPEC_FILE and CLOUD_SPEC_FILE in GcsBucketSpecFetcher.java
default_spec_file="spec.json"
cloud_spec_file="spec.cloud.json"

_check_tag_exists() {
  DOCKER_CLI_EXPERIMENTAL=enabled docker manifest inspect "$1" > /dev/null
}

_error_if_tag_exists() {
    if _check_tag_exists "$1"; then
      error "You're trying to push a version that was already released ($1). Make sure you bump it up."
    fi
}

cmd_publish() {
  local path=$1; shift || error "Missing target (root path of integration) $USAGE"
  [ -d "$path" ] || error "Path must be the root path of the integration"

  local run_tests=$1; shift || run_tests=true
  local publish_spec_to_cache
  local pre_release
  local spec_cache_writer_sa_key_file

  while [ $# -ne 0 ]; do
    case "$1" in
    --publish_spec_to_cache)
      publish_spec_to_cache=true
      shift 1
      ;;
    --pre_release)
      pre_release=true
      shift 1
      ;;
    --publish_spec_to_cache_with_key_file)
      publish_spec_to_cache=true
      spec_cache_writer_sa_key_file="$2"
      shift 2
      ;;
    *)
      error "Unknown option: $1"
      ;;
    esac
  done

  publish_spec_to_cache=false

  # setting local variables for docker image versioning
  local image_name; image_name=$(_get_docker_image_name "$path"/Dockerfile)
  local image_version; image_version=$(_get_docker_image_version "$path"/Dockerfile "$pre_release")
  local versioned_image=$image_name:$image_version
  local latest_image="$image_name" # don't include ":latest", that's assumed here
  local build_arch="linux/amd64,linux/arm64"

  # learn about this version of Docker
  echo "--- docker info ---"
  docker --version
  docker buildx version

  # Install docker emulators
  # TODO: Don't run this command on M1 macs locally (it won't work and isn't needed)
  apt-get update && apt-get install -y qemu-user-static

  # log into docker
  if test -z "${DOCKER_HUB_USERNAME}"; then
    echo 'DOCKER_HUB_USERNAME not set.';
    exit 1;
  fi

  if test -z "${DOCKER_HUB_PASSWORD}"; then
    echo 'DOCKER_HUB_PASSWORD for docker user not set.';
    exit 1;
  fi

  set +x
  DOCKER_TOKEN=$(curl -s -H "Content-Type: application/json" -X POST -d '{"username": "'${DOCKER_HUB_USERNAME}'", "password": "'${DOCKER_HUB_PASSWORD}'"}' https://hub.docker.com/v2/users/login/ | jq -r .token)
  set -x

  echo "image_name $image_name"
  echo "versioned_image $versioned_image"

  if [ "$pre_release" == "true" ]
  then
    echo "will skip updating latest_image $latest_image tag due to pre_release"
  else
    echo "latest_image $latest_image"
  fi

  _error_if_tag_exists "$versioned_image"

  # We have to go arch-by-arch locally (see https://github.com/docker/buildx/issues/59 for more info) due to our base images (e.g. airbyte-integrations/bases/base-java)
  # Alternative local approach @ https://github.com/docker/buildx/issues/301#issuecomment-755164475
  # We need to use the regular docker buildx driver (not docker container) because we need this intermediate contaiers to be available for later build steps

  echo Installing arm64 docker emulation
  docker run --privileged --rm tonistiigi/binfmt --install arm64

  for arch in $(echo $build_arch | sed "s/,/ /g")
  do
    local arch_versioned_image=$image_name:`echo $arch | sed "s/\//-/g"`-$image_version
    echo "Publishing new version ($arch_versioned_image) from $path"
    docker buildx build -t $arch_versioned_image --platform $arch --push $path
    docker manifest create $versioned_image --amend $arch_versioned_image

    if [ "$pre_release" != "true" ]; then
      docker manifest create $latest_image --amend $arch_versioned_image
    fi

  done

  docker manifest push $versioned_image
  docker manifest rm $versioned_image

  if [ "$pre_release" != "true" ]; then
    docker manifest push $latest_image
    docker manifest rm $latest_image
  fi

  # delete the temporary image tags made with arch_versioned_image
  sleep 10
  for arch in $(echo $build_arch | sed "s/,/ /g")
  do
    local arch_versioned_tag=`echo $arch | sed "s/\//-/g"`-$image_version
    echo "deleting temporary tag: ${image_name}/tags/${arch_versioned_tag}"
    TAG_URL="https://hub.docker.com/v2/repositories/${image_name}/tags/${arch_versioned_tag}/" # trailing slash is needed!
    set +x
    curl -X DELETE -H "Authorization: JWT ${DOCKER_TOKEN}" "$TAG_URL"
    set -x
  done


  # Checking if the image was successfully registered on DockerHub
  # see the description of this PR to understand why this is needed https://github.com/airbytehq/airbyte/pull/11654/
  sleep 5

  # To work for private repos we need a token as well
  TAG_URL="https://hub.docker.com/v2/repositories/${image_name}/tags/${image_version}"
  set +x
  DOCKERHUB_RESPONSE_CODE=$(curl --silent --output /dev/null --write-out "%{http_code}" -H "Authorization: JWT ${DOCKER_TOKEN}" ${TAG_URL})
  set -x
  if [[ "${DOCKERHUB_RESPONSE_CODE}" == "404" ]]; then
    echo "Tag ${image_version} was not registered on DockerHub for image ${image_name}, please try to bump the version again." && exit 1
  fi
}

main() {
  assert_root

  local cmd=$1; shift || error "Missing cmd $USAGE"
  cmd_"$cmd" "$@"
}

main "$@"
