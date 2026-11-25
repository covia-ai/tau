#!/bin/sh
# Prints the main agent's id: config.json's chat.agentId, or Tau when absent.
# config.json is JSON5 (comments, unquoted keys), so read it by pattern.
#   main-agent.sh [config.json]
home="${TAU_HOME:-$HOME/.tau}"
config="${1:-$home/config.json}"
command -v cygpath >/dev/null 2>&1 && config=$(cygpath -w "$config")
python -I - "$config" <<'PY'
import re, sys
try:
	text = open(sys.argv[1], encoding="utf-8").read()
except OSError:
	text = ""
text = re.sub(r"//[^\n]*", "", text)
m = re.search(r"""["']?chat["']?\s*:\s*\{(.*?)\}""", text, re.S)
a = m and re.search(r"""["']?agentId["']?\s*:\s*["']([^"']+)["']""", m.group(1))
print(a.group(1) if a else "Tau")
PY
