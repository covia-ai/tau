#!/usr/bin/env bash
# End-to-end check of an installer on macOS or Linux: installs it as a person
# would, runs the installed app's own launcher with --self-test twice in a
# throwaway folder (the second run proves the store reopens), checks the
# bundled runtime was the one used, uninstalls, and checks that user data was
# left alone.
#
#   packaging/e2e-installed.sh dmg      target/installer/tau-*.dmg
#   packaging/e2e-installed.sh deb      target/installer/tau-*.deb
#   packaging/e2e-installed.sh appimage target/installer/tau-*.AppImage
#
# Writes into E2E_DIR (default target/e2e): the self-test folder and its
# report and logs, for upload when a step fails. Installing a deb needs sudo.
# The check that ~/.tau survives uninstalling runs only in CI (GITHUB_ACTIONS),
# and only when no ~/.tau exists yet: a developer's real data is never touched.
set -euo pipefail

type="${1:?installer type: dmg, deb or appimage}"
installer="$(cd "$(dirname "${2:?installer file}")" && pwd)/$(basename "$2")"
cd "$(dirname "$0")/.."
work="$(mkdir -p "${E2E_DIR:-target/e2e}" && cd "${E2E_DIR:-target/e2e}" && pwd)"
data="$work/self-test"
rm -rf "$data"

step() { echo "== $*"; }
die() { echo "::error::$*"; exit 1; }

# A sentinel in ~/.tau, made only on a CI runner that has none, to prove the
# installer and uninstaller leave the data directory alone.
sentinel=""
if [[ "${GITHUB_ACTIONS:-}" == true && ! -e "$HOME/.tau" ]]; then
	sentinel="$HOME/.tau/e2e-sentinel.txt"
	mkdir -p "$HOME/.tau"
	echo "Left by the installer end-to-end check; must survive uninstalling." > "$sentinel"
	sentinel_sum="$(cksum < "$sentinel")"
fi

case "$type" in
	dmg)
		step "Install: mount the dmg and copy Tau.app, as a person drags it"
		mnt="$work/dmg"
		apps="$work/Applications"
		rm -rf "$apps"
		mkdir -p "$mnt" "$apps"
		hdiutil attach -nobrowse -readonly -mountpoint "$mnt" "$installer" >/dev/null
		cp -R "$mnt/Tau.app" "$apps/"
		hdiutil detach "$mnt" >/dev/null
		launcher="$apps/Tau.app/Contents/MacOS/Tau"
		runtime="$apps/Tau.app/Contents/runtime"
		uninstall() { rm -rf "$apps/Tau.app"; }
		installed() { [[ -e "$apps/Tau.app" ]]; }
		;;
	deb)
		step "Install: apt installs the deb with its dependencies"
		sudo apt-get install -y -q "$installer"
		launcher="/opt/tau/bin/Tau"
		runtime="/opt/tau/lib/runtime"
		uninstall() { sudo apt-get remove -y -q tau; }
		# Removed but with configuration left reads "deinstall ok config-files": not installed.
		installed() { dpkg-query -W -f='${Status}' tau 2>/dev/null | grep -q 'install ok installed'; }
		;;
	appimage)
		step "Install: make the AppImage executable"
		chmod +x "$installer"
		launcher="$installer"
		# An AppImage unpacks itself to a temporary folder to run. Wherever that is, the bundled
		# runtime sits at usr/lib/runtime inside it (packaging/package.sh lays the image out there).
		runtime="/usr/lib/runtime"
		export APPIMAGE_EXTRACT_AND_RUN=1
		uninstall() { :; }
		installed() { false; }
		;;
	*) die "Unknown installer type: $type" ;;
esac
[[ -x "$launcher" ]] || die "No launcher at $launcher"

# Some JDK on the machine (the one that built the installer) must not stand in.
unset JAVA_HOME JDK_JAVA_OPTIONS JAVA_TOOL_OPTIONS
unset TAU_CHARACTER_DIR

for run in first reopen; do
	step "Self-test, $run run"
	set +e
	(cd "$work" && "$launcher" --self-test "$data")
	code=$?
	set -e
	[[ -f "$data/self-test-report.txt" ]] || die "The $run run wrote no report (exit $code)"
	[[ $code == 0 ]] || die "The $run run failed with exit code $code"
done

step "Check the report"
report="$data/self-test-report.txt"
[[ "$(grep -c '^RESULT PASS' "$report")" == 2 ]] || die "Not both runs passed"
grep -q '^PASS history:' "$report" || die "The reopen run did not find the first run's chat"
runtime_line="$(grep -m1 '^PASS runtime:' "$report")"
echo "$runtime_line"
[[ "$runtime_line" == *"$runtime"* ]] || die "The self-test ran on another Java, not the bundled runtime ($runtime)"

step "Uninstall"
uninstall
if installed; then die "Still installed after uninstalling"; fi
[[ -f "$report" ]] || die "Uninstalling removed the self-test's data folder"

if [[ -n "$sentinel" ]]; then
	step "Check ~/.tau was left alone"
	[[ -f "$sentinel" && "$(cksum < "$sentinel")" == "$sentinel_sum" ]] || die "Installing or uninstalling changed ~/.tau"
	rm -rf "$HOME/.tau"
fi
echo "End-to-end check passed: $type"
