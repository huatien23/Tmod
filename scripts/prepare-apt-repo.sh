#!/usr/bin/env bash
set -euo pipefail

packages_dir="${1:?termux-packages directory required}"
bootstrap="${2:?bootstrap zip required}"
site_dir="${3:?site output directory required}"

mkdir -p "$site_dir/apt/pool/main" "$site_dir/apt/dists/stable/main/binary-aarch64"
shopt -s nullglob
packages=("$packages_dir"/output/*.deb)
((${#packages[@]} > 0)) || { echo 'No rebuilt .deb packages found' >&2; exit 1; }
selected=()
for package in "${packages[@]}"; do
  case "${package##*/}" in
    dpkg-perl_*|dpkg-scanpackages_*) continue ;;
  esac
  cp "$package" "$site_dir/apt/pool/main/"
  selected+=("$package")
done
(
  cd "$site_dir/apt"
  dpkg-scanpackages --multiversion pool/main /dev/null > dists/stable/main/binary-aarch64/Packages
  gzip -9 -k dists/stable/main/binary-aarch64/Packages
  apt-ftparchive -o APT::FTPArchive::Release::Suite=stable -o APT::FTPArchive::Release::Codename=stable -o APT::FTPArchive::Release::Origin=Tmod release dists/stable > dists/stable/Release
)

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
unzip -q "$bootstrap" -d "$tmp_dir"
mkdir -p "$tmp_dir/etc/apt"
rm -rf "$tmp_dir/etc/apt/sources.list.d"
printf '%s\n' 'deb [trusted=yes] https://huatien23.github.io/Tmod/apt stable main' > "$tmp_dir/etc/apt/sources.list"
if [[ -f "$tmp_dir/SYMLINKS.txt" ]]; then
  sed -i '\@←./etc/apt/sources.list@d;\@←etc/apt/sources.list@d' "$tmp_dir/SYMLINKS.txt"
fi
rm -f "$bootstrap"
(cd "$tmp_dir" && zip -qr9 "$bootstrap" .)
unzip -tq "$bootstrap" >/dev/null
printf 'Prepared %s packages and %s\n' "${#selected[@]}" "$bootstrap"
