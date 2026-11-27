#!/usr/bin/env bash
# Fails when a committed image file is not matched by packaging/allowed-images.txt.
set -euo pipefail
cd "$(dirname "$0")/.."

patterns=()
while IFS= read -r line; do
	patterns+=("${line%$'\r'}") # a Windows-style checkout ends each line with CR
done < <(grep -v '^[[:space:]]*#' packaging/allowed-images.txt | grep -v '^[[:space:]]*$')

unexpected=0
while IFS= read -r file; do
	allowed=0
	for pattern in "${patterns[@]}"; do
		# shellcheck disable=SC2053 # the pattern is a glob on purpose
		if [[ "$file" == $pattern ]]; then
			allowed=1
			break
		fi
	done
	if [[ $allowed == 0 ]]; then
		echo "::error file=$file::Image not on packaging/allowed-images.txt: $file"
		unexpected=1
	fi
done < <(git ls-files | grep -iE '\.(png|jpe?g|gif|webp|bmp|tiff?|svg|ico|icns|psd|riv|lottie|heic|avif)$')

if [[ $unexpected == 0 ]]; then
	echo "Every committed image is on the allowed list."
fi
exit $unexpected
