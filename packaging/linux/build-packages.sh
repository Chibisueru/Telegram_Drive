#!/usr/bin/env bash
# Builds the .deb and .rpm from the app image made by `./gradlew :desktop:createDistributable`.
# Needs JDK 17+ (jpackage), dpkg-deb + fakeroot for .deb, rpmbuild for .rpm.
#   packaging/linux/build-packages.sh <app-image-dir> <version> <out-dir> [deb] [rpm]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$1"; version="$2"; out="$3"; shift 3
types=("$@"); [ ${#types[@]} -eq 0 ] && types=(deb rpm)
mkdir -p "$out"
common=(--app-image "$app" --name TelegramDrive --app-version "$version" --dest "$out"
  --description "Cloud drive that stores your files in your own Telegram account"
  --vendor Chibisueru --copyright "© 2026 Chibisueru"
  --linux-package-name telegram-drive --linux-shortcut
  --linux-menu-group "Network;FileTransfer" --linux-app-category Network
  --resource-dir "$here/resources")
for t in "${types[@]}"; do
  case "$t" in
    deb) jpackage --type deb "${common[@]}" --linux-deb-maintainer "Chibisueru <noreply@github.com>" ;;
    rpm) jpackage --type rpm "${common[@]}" --linux-rpm-license-type Unspecified ;;
  esac
done
ls -la "$out"
