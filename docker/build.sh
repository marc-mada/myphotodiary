#!/bin/bash
# Builds the backend jar and the frontend, then the image, tagged with the
# version from pom.xml.
#   docker/build.sh           this machine's architecture only -> myphotodiary:<version>
#   docker/build.sh --all     Intel (amd64) and ARM (arm64) under one name,
#                             kept in the local image store (Docker picks the
#                             right one; run the other with --platform)
#   docker/build.sh --push <image>
#                             both architectures, published as <image>:<version>
#                             and <image>:latest (e.g. ghcr.io/owner/myphotodiary;
#                             needs `docker login` to that registry first)

set -euo pipefail
cd "$(dirname "$0")/.."

version="$(sed -n 's:.*<version>\([0-9][^<]*\)</version>.*:\1:p' pom.xml | head -1)"
mode="${1:-}"
echo "Building myPhotoDiary $version"

mvn -q clean package -DskipTests

case "$mode" in
	"")
		docker build -f docker/Dockerfile --build-arg VERSION="$version" -t "myphotodiary:$version" .
		echo "Built image myphotodiary:$version"
		;;
	--all)
		docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile \
			--build-arg VERSION="$version" -t "myphotodiary:$version" --load .
		echo "Built image myphotodiary:$version for linux/amd64 and linux/arm64"
		;;
	--push)
		image="${2:?usage: docker/build.sh --push <image, e.g. ghcr.io/owner/myphotodiary>}"
		docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile \
			--build-arg VERSION="$version" -t "$image:$version" -t "$image:latest" --push .
		echo "Published $image:$version and $image:latest for linux/amd64 and linux/arm64"
		;;
	*)
		echo "usage: docker/build.sh [--all | --push <image>]" >&2
		exit 2
		;;
esac
