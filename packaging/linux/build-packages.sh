#!/usr/bin/env bash
# Builds the .deb and .rpm from the app image made by `./gradlew :desktop:createDistributable`.
# The menu entry, icons and /usr/bin link are ordinary package files (not install scripts), so
# upgrades can't remove them. Needs dpkg-deb for .deb and rpmbuild for .rpm.
#   packaging/linux/build-packages.sh <app-image-dir> <version> <out-dir> [deb] [rpm]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$(cd "$1" && pwd)"; version="$2"; mkdir -p "$3"; out="$(cd "$3" && pwd)"; shift 3
types=("$@"); [ ${#types[@]} -eq 0 ] && types=(deb rpm)
summary="Cloud drive that stores your files in your own Telegram account"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# One file tree, shared by both formats.
stage="$work/root"
mkdir -p "$stage/opt/telegram-drive" "$stage/usr/bin" "$stage/usr/share/applications"
cp -a "$app/." "$stage/opt/telegram-drive/"
ln -s /opt/telegram-drive/bin/TelegramDrive "$stage/usr/bin/telegram-drive"
install -m644 "$here/telegram-drive.desktop" "$stage/usr/share/applications/telegram-drive.desktop"
for dir in "$here"/icons/*/; do
  size="$(basename "$dir")"
  install -Dm644 "$dir/telegram-drive.png" "$stage/usr/share/icons/hicolor/$size/apps/telegram-drive.png"
done
chmod -R u+rwX,go+rX,go-w "$stage"

for t in "${types[@]}"; do
  case "$t" in
  deb)
    deb="$work/deb"
    cp -a "$stage" "$deb"
    mkdir -p "$deb/DEBIAN"
    size_kb="$(du -sk "$deb/opt" | cut -f1)"
    cat > "$deb/DEBIAN/control" <<CONTROL
Package: telegram-drive
Version: $version
Architecture: amd64
Maintainer: Chibisueru <noreply@github.com>
Installed-Size: $size_kb
Depends: libc6 (>= 2.34), libstdc++6, libssl3 | libssl3t64, zlib1g, libfreetype6, libfontconfig1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libasound2 | libasound2t64
Recommends: xdg-utils
Section: net
Priority: optional
Homepage: https://github.com/Chibisueru/Telegram_Drive
Description: $summary
 Stores files in a private channel in your own Telegram account, with folders,
 favorites and trash. Shares the same drive with the Android app.
CONTROL
    dpkg-deb --root-owner-group -Zxz --build "$deb" "$out/telegram-drive_${version}_amd64.deb" >/dev/null
    ;;
  rpm)
    top="$work/rpmbuild"
    mkdir -p "$top"/{BUILD,RPMS,SPECS,SOURCES,SRPMS}
    cat > "$top/SPECS/telegram-drive.spec" <<SPEC
Name:           telegram-drive
Version:        $version
Release:        1
Summary:        $summary
License:        LicenseRef-Unknown
URL:            https://github.com/Chibisueru/Telegram_Drive
BuildArch:      x86_64
# The bundled Java runtime brings its own libraries; only list what the system must provide.
AutoReqProv:    no
Requires:       glibc >= 2.34, libstdc++, openssl-libs, zlib, freetype, fontconfig, libX11, libXext, libXi, libXrender, libXtst, alsa-lib
Recommends:     xdg-utils
%global debug_package %{nil}
%global __strip /bin/true
%global _build_id_links none
%global __brp_check_rpaths %{nil}

%description
Stores files in a private channel in your own Telegram account, with folders,
favorites and trash. Shares the same drive with the Android app.

%install
cp -a "$stage/." %{buildroot}/

%files
/opt/telegram-drive
/usr/bin/telegram-drive
/usr/share/applications/telegram-drive.desktop
/usr/share/icons/hicolor/*/apps/telegram-drive.png
SPEC
    rpmbuild --define "_topdir $top" -bb "$top/SPECS/telegram-drive.spec" >/dev/null
    cp "$top"/RPMS/x86_64/*.rpm "$out/"
    ;;
  esac
done
ls -la "$out"
