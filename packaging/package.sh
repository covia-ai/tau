#!/usr/bin/env bash
# Builds Tau's native installers from target/tau.jar with jlink and jpackage.
#
#   packaging/package.sh runtime          link the bundled Java runtime
#   packaging/package.sh image            runtime, then the application image
#   packaging/package.sh installer TYPE   an installer from the image: dmg, msi or deb
#   packaging/package.sh appimage         an AppImage from the image (Linux)
#   packaging/package.sh all              every format this operating system builds
#
# Run from the repository root after `mvn package`, with a JDK 25 on PATH or
# in JAVA_HOME. Output goes to target/installer/. Signing is applied between
# `image` and `installer` by the CI workflow when its secrets are present;
# set TAU_MAC_SIGN_IDENTITY to have jpackage sign on macOS.
set -euo pipefail

cd "$(dirname "$0")/.."

APP_NAME="Tau"
PACKAGE_NAME="tau"
VENDOR="Covia"
BUNDLE_ID="ai.covia.tau"
DESCRIPTION="Your own agent, on your own machine, under your own identity."
COPYRIGHT="Copyright (c) Covia. Eclipse Public License 2.0."
# Fixed so a newer MSI replaces an older one instead of installing beside it.
WIN_UPGRADE_UUID="8F6DA7FA-9EEC-48D5-B2FC-5F50E16675D3"
JAVA_OPTIONS="--enable-native-access=ALL-UNNAMED"

OUT="target/installer"
WORK="target/jpackage"
RUNTIME="$WORK/runtime"
ICONS="src/main/resources/icons/tau"

case "$(uname -s)" in
	Darwin) OS=mac ;;
	Linux) OS=linux ;;
	MINGW*|MSYS*|CYGWIN*) OS=windows ;;
	*) echo "Unsupported operating system: $(uname -s)" >&2; exit 1 ;;
esac

if [[ -n "${JAVA_HOME:-}" ]]; then
	if [[ "$OS" == windows ]]; then
		BIN="$(cygpath -u "$JAVA_HOME")/bin/"
	else
		BIN="$JAVA_HOME/bin/"
	fi
else
	BIN=""
fi

# The version installers carry: numeric only, so 0.1.0-SNAPSHOT becomes 0.1.0.
# macOS refuses a first number of 0, so there the 0.x preview line is shown as
# 1.x (0.1.0 becomes 1.1.0); elsewhere the plain version is used.
# TAU_APP_VERSION overrides both. The version Tau reports in About comes from
# the jar (tau.BuildInfo), not from this.
pom_version() {
	sed -n '/<artifactId>tau<\/artifactId>/,/<\/version>/s:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1
}
app_version() {
	if [[ -n "${TAU_APP_VERSION:-}" ]]; then
		echo "$TAU_APP_VERSION"
		return
	fi
	local v
	v="$(pom_version)"
	v="${v%%-*}"
	if [[ "$OS" == mac && "${v%%.*}" == 0 ]]; then
		echo "1.${v#0.}"
	else
		echo "$v"
	fi
}

image_dir() {
	case "$OS" in
		mac) echo "$WORK/image/$APP_NAME.app" ;;
		*) echo "$WORK/image/$APP_NAME" ;;
	esac
}

icon() {
	mkdir -p "$WORK"
	case "$OS" in
		mac)
			local set="$WORK/$PACKAGE_NAME.iconset"
			rm -rf "$set"
			mkdir -p "$set"
			for s in 16 32 128 256 512; do
				cp "$ICONS/tau-$s.png" "$set/icon_${s}x${s}.png"
			done
			cp "$ICONS/tau-32.png" "$set/icon_16x16@2x.png"
			cp "$ICONS/tau-64.png" "$set/icon_32x32@2x.png"
			cp "$ICONS/tau-256.png" "$set/icon_128x128@2x.png"
			cp "$ICONS/tau-512.png" "$set/icon_256x256@2x.png"
			cp "$ICONS/tau-1024.png" "$set/icon_512x512@2x.png"
			iconutil -c icns "$set" -o "$WORK/$PACKAGE_NAME.icns"
			echo "$WORK/$PACKAGE_NAME.icns"
			;;
		windows)
			"${BIN}java" packaging/MakeIco.java "$WORK/$PACKAGE_NAME.ico" \
				"$ICONS/tau-16.png" "$ICONS/tau-32.png" "$ICONS/tau-48.png" \
				"$ICONS/tau-64.png" "$ICONS/tau-128.png" "$ICONS/tau-256.png"
			echo "$WORK/$PACKAGE_NAME.ico"
			;;
		linux)
			echo "$ICONS/tau-256.png"
			;;
	esac
}

runtime() {
	[[ -f target/tau.jar ]] || { echo "target/tau.jar is missing: run mvn package first" >&2; exit 1; }
	local modules
	modules="$(grep -v '^[[:space:]]*#' packaging/runtime-modules.txt | grep -v '^[[:space:]]*$' | paste -sd, -)"
	rm -rf "$RUNTIME"
	mkdir -p "$WORK"
	# Native commands are kept: Tau restarts itself with the runtime's own
	# bin/java (tau.Relaunch), which jpackage's default runtime strips.
	"${BIN}jlink" \
		--add-modules "$modules" \
		--strip-debug \
		--no-man-pages \
		--no-header-files \
		--compress zip-6 \
		--output "$RUNTIME"
	echo "Runtime: $RUNTIME ($(du -sh "$RUNTIME" | cut -f1))"
}

image() {
	runtime
	local input="$WORK/input"
	rm -rf "$input" "$WORK/image"
	mkdir -p "$input" "$WORK/image"
	cp target/tau.jar "$input/"

	local args=(
		--type app-image
		--name "$APP_NAME"
		--app-version "$(app_version)"
		--vendor "$VENDOR"
		--description "$DESCRIPTION"
		--copyright "$COPYRIGHT"
		--input "$input"
		--main-jar tau.jar
		--main-class tau.Main
		--java-options "$JAVA_OPTIONS"
		--runtime-image "$RUNTIME"
		--icon "$(icon)"
		--dest "$WORK/image"
	)
	if [[ "$OS" == mac ]]; then
		args+=(--mac-package-identifier "$BUNDLE_ID" --mac-package-name "$APP_NAME" --mac-app-category public.app-category.productivity)
		if [[ -n "${TAU_MAC_SIGN_IDENTITY:-}" ]]; then
			args+=(--mac-sign --mac-signing-key-user-name "$TAU_MAC_SIGN_IDENTITY" --mac-entitlements packaging/macos/entitlements.plist)
			[[ -n "${TAU_MAC_KEYCHAIN:-}" ]] && args+=(--mac-signing-keychain "$TAU_MAC_KEYCHAIN")
		fi
	fi
	"${BIN}jpackage" "${args[@]}"
	echo "Image: $(image_dir)"
}

installer() {
	local type="$1"
	local img
	img="$(image_dir)"
	[[ -d "$img" ]] || { echo "No application image at $img: run '$0 image' first" >&2; exit 1; }
	mkdir -p "$OUT"
	local args=(
		--type "$type"
		--name "$APP_NAME"
		--app-version "$(app_version)"
		--vendor "$VENDOR"
		--description "$DESCRIPTION"
		--copyright "$COPYRIGHT"
		--app-image "$img"
		--dest "$OUT"
	)
	case "$type" in
		dmg)
			args+=(--mac-package-identifier "$BUNDLE_ID" --mac-package-name "$APP_NAME")
			if [[ -n "${TAU_MAC_SIGN_IDENTITY:-}" ]]; then
				args+=(--mac-sign --mac-signing-key-user-name "$TAU_MAC_SIGN_IDENTITY")
				[[ -n "${TAU_MAC_KEYCHAIN:-}" ]] && args+=(--mac-signing-keychain "$TAU_MAC_KEYCHAIN")
			fi
			;;
		msi)
			# The licence is shown by the MSI and recorded with the deb. A dmg
			# licence prompt would block scripted mounting, so the dmg has none.
			args+=(--license-file LICENSE --win-upgrade-uuid "$WIN_UPGRADE_UUID" --win-menu --win-menu-group "$APP_NAME" --win-shortcut --win-dir-chooser)
			;;
		deb)
			args+=(--license-file LICENSE --linux-package-name "$PACKAGE_NAME" --linux-app-category utils --linux-menu-group "Utility;Network;"
				--linux-shortcut)
			;;
		*) echo "Unknown installer type: $type" >&2; exit 1 ;;
	esac
	"${BIN}jpackage" "${args[@]}"
	rename_output "$type"
}

# Names each installer for its platform and architecture, so the files from
# every CI job can sit side by side in one release.
rename_output() {
	local type="$1" arch file
	arch="$(uname -m)"
	case "$arch" in
		x86_64|amd64) arch=x64 ;;
		arm64|aarch64) arch=arm64 ;;
	esac
	# jpackage names its output differently per type; take the newest.
	# shellcheck disable=SC2012
	file="$(ls -t "$OUT"/*."$type" | head -1)"
	local target
	target="$OUT/$PACKAGE_NAME-$(pom_version)-$OS-$arch.$type"
	[[ "$file" == "$target" ]] || mv -f "$file" "$target"
	echo "Installer: $target"
}

# An AppImage is the application image plus an AppRun entry point and a
# desktop file, squashed by appimagetool (APPIMAGETOOL, or on PATH).
appimage() {
	[[ "$OS" == linux ]] || { echo "AppImages are built on Linux" >&2; exit 1; }
	local img tool appdir arch
	img="$(image_dir)"
	[[ -d "$img" ]] || { echo "No application image at $img: run '$0 image' first" >&2; exit 1; }
	tool="${APPIMAGETOOL:-appimagetool}"
	appdir="$WORK/$APP_NAME.AppDir"
	rm -rf "$appdir"
	mkdir -p "$appdir/usr" "$OUT"
	cp -a "$img/." "$appdir/usr/"
	cp packaging/linux/tau.desktop "$appdir/$PACKAGE_NAME.desktop"
	cp "$ICONS/tau-256.png" "$appdir/$PACKAGE_NAME.png"
	ln -s "$PACKAGE_NAME.png" "$appdir/.DirIcon"
	cat > "$appdir/AppRun" <<-'EOF'
	#!/bin/sh
	HERE="$(dirname "$(readlink -f "$0")")"
	exec "$HERE/usr/bin/Tau" "$@"
	EOF
	chmod +x "$appdir/AppRun"
	arch="$(uname -m)"
	ARCH="$arch" "$tool" --no-appstream "$appdir" "$OUT/$PACKAGE_NAME-$(pom_version)-linux-${arch/x86_64/x64}.AppImage"
	echo "Installer: $OUT/$PACKAGE_NAME-$(pom_version)-linux-${arch/x86_64/x64}.AppImage"
}

case "${1:-}" in
	runtime) runtime ;;
	image) image ;;
	installer) installer "${2:?installer type: dmg, msi or deb}" ;;
	appimage) appimage ;;
	all)
		image
		case "$OS" in
			mac) installer dmg ;;
			windows) installer msi ;;
			linux) installer deb; appimage ;;
		esac
		;;
	*) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
