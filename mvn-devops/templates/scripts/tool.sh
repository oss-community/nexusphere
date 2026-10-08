#!/bin/sh
# Prints the path of a pinned command line tool, downloading it from its
# official release on first use and checking it against the release checksums.
#
#   tool.sh <trivy|syft|cosign|helm|kubectl|kubeseal|jq|k6>
#
# Tools are cached in $DEVOPS_TOOLS (default ~/.cache/mvn-devops/tools), one
# directory per version.  <NAME>_VERSION overrides a version, e.g.
# TRIVY_VERSION=0.75.0; DEVOPS_RELEASES_MIRROR replaces https://github.com
# for networks that reach GitHub through a mirror.
set -eu

name=$1
case $(uname -s) in
  Linux) os=linux ;;
  Darwin) os=darwin ;;
  MINGW* | MSYS* | CYGWIN*) os=windows ;;
  *) echo "tool.sh: unsupported system $(uname -s)" >&2; exit 1 ;;
esac
case $(uname -m) in
  x86_64 | amd64) arch=amd64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) echo "tool.sh: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
exe='' member=''
[ $os = windows ] && exe=.exe
github=${DEVOPS_RELEASES_MIRROR:-https://github.com}

case $name in
  trivy)
    version=${TRIVY_VERSION:-0.75.0}
    case $os in linux) o=Linux ;; darwin) o=macOS ;; windows) o=windows ;; esac
    case $arch in amd64) a=64bit ;; arm64) a=ARM64 ;; esac
    ext=tar.gz; [ $os = windows ] && ext=zip
    url=$github/aquasecurity/trivy/releases/download/v$version
    asset=trivy_${version}_$o-$a.$ext
    sums=trivy_${version}_checksums.txt ;;
  syft)
    version=${SYFT_VERSION:-1.54.1}
    ext=tar.gz; [ $os = windows ] && ext=zip
    url=$github/anchore/syft/releases/download/v$version
    asset=syft_${version}_${os}_$arch.$ext
    sums=syft_${version}_checksums.txt ;;
  cosign)
    version=${COSIGN_VERSION:-3.1.3}
    url=$github/sigstore/cosign/releases/download/v$version
    asset=cosign-$os-$arch$exe
    sums=cosign_checksums.txt ;;
  helm)
    version=${HELM_VERSION:-4.3.0}
    ext=tar.gz; [ $os = windows ] && ext=zip
    url=${DEVOPS_HELM_MIRROR:-https://get.helm.sh}
    asset=helm-v$version-$os-$arch.$ext
    sums=$asset.sha256sum
    member=$os-$arch/helm$exe ;;
  kubectl)
    version=${KUBECTL_VERSION:-1.37.1}
    url=${DEVOPS_KUBECTL_MIRROR:-https://dl.k8s.io/release}/v$version/bin/$os/$arch
    asset=kubectl$exe
    sums=kubectl$exe.sha256 ;;
  kubeseal)
    version=${KUBESEAL_VERSION:-0.40.0}
    url=$github/bitnami-labs/sealed-secrets/releases/download/v$version
    asset=kubeseal-$version-$os-$arch.tar.gz
    sums=sealed-secrets_${version}_checksums.txt
    member=kubeseal$exe ;;
  jq)
    version=${JQ_VERSION:-1.8.1}
    case $os in darwin) o=macos ;; *) o=$os ;; esac
    url=$github/jqlang/jq/releases/download/jq-$version
    asset=jq-$o-$arch$exe
    sums=sha256sum.txt ;;
  k6)
    version=${K6_VERSION:-2.3.0}
    case $os in darwin) o=macos ;; *) o=$os ;; esac
    ext=tar.gz; [ $os = linux ] || ext=zip
    url=$github/grafana/k6/releases/download/v$version
    asset=k6-v$version-$o-$arch.$ext
    sums=k6-v$version-checksums.txt
    member=k6-v$version-$o-$arch/k6$exe ;;
  *) echo "tool.sh: unknown tool $name" >&2; exit 1 ;;
esac

dir=${DEVOPS_TOOLS:-$HOME/.cache/mvn-devops/tools}/$name-$version
bin=$dir/$name$exe
if [ ! -x "$bin" ]; then
  mkdir -p "$dir"
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  echo "Downloading $name $version" >&2
  curl -fsSL -o "$tmp/$asset" "$url/$asset"
  curl -fsSL -o "$tmp/sums" "$url/$sums"
  expected=$(awk -v f="$asset" '$2 == f || $2 == "*" f || NF == 1 { print $1; exit }' "$tmp/sums")
  if command -v sha256sum > /dev/null; then
    actual=$(sha256sum "$tmp/$asset" | awk '{ print $1 }')
  else
    actual=$(shasum -a 256 "$tmp/$asset" | awk '{ print $1 }')
  fi
  if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
    echo "tool.sh: checksum of $asset does not match $sums" >&2
    exit 1
  fi
  member=${member:-$name$exe}
  case $asset in
    *.tar.gz) tar -xzf "$tmp/$asset" -C "$tmp" "$member" ;;
    *.zip) unzip -q -o "$tmp/$asset" "$member" -d "$tmp" ;;
    *) [ "$asset" = "$member" ] || mv "$tmp/$asset" "$tmp/$member" ;;
  esac
  [ "$member" = "$name$exe" ] || mv "$tmp/$member" "$tmp/$name$exe"
  chmod +x "$tmp/$name$exe"
  mv "$tmp/$name$exe" "$bin"
fi
printf '%s\n' "$bin"
