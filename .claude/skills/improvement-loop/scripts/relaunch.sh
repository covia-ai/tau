#!/bin/sh
# Starts Tau from a jar, taking over the one running if there is one, waits
# until the new process answers, and prints its info.
#   relaunch.sh [jar]   jar: ~/.tau/code/tau/target/tau.jar by default;
#                       ~/.tau/code/last-good.jar is the rollback
# The jar's own --relaunch starts the new Tau outside this session's Windows
# job, so it outlives the session. It unlocks with the remembered passphrase
# and signs the takeover with the venue's key, so no login is kept for it;
# without a remembered passphrase it refuses.
here=$(dirname "$0")
home="${TAU_HOME:-$HOME/.tau}"
jar="${1:-$home/code/tau/target/tau.jar}"
[ -f "$jar" ] || { echo "No jar at $jar" >&2; exit 2; }
config="$home/config.json"
if command -v cygpath >/dev/null 2>&1; then jar=$(cygpath -w "$jar"); config=$(cygpath -w "$config"); fi

pid_now() {
	"$here/mcp.sh" grid_run '{"operation":"v/ops/tau/info"}' 2>/dev/null \
		| python -I -c "import json,sys;print(json.loads(sys.stdin.read()).get('pid',''))" 2>/dev/null
}

# Whether a process is still there: Git Bash on Windows lists Windows ids
# with ps -W (fourth column); elsewhere, kill -0.
alive() {
	if ps -W >/dev/null 2>&1; then
		ps -W | awk -v p="$1" '$4 == p { found = 1 } END { exit !found }'
	else
		kill -0 "$1" 2>/dev/null
	fi
}

before=$(pid_now)
out=$(java -jar "$jar" --relaunch "$config") || { printf '%s\n' "$out" >&2; exit 1; }
printf '%s\n' "$out"
new=$(printf '%s\n' "$out" | sed -n 's/.* as process \([0-9]*\).*/\1/p')
[ -n "$new" ] || { echo "No process id in the launcher's answer" >&2; exit 1; }

# The new process unlocks, asks the old one to stop, waits for its store, and
# opens the venue. Wait until it is the one answering (up to five minutes).
i=0
while [ $i -lt 100 ]; do
	pid=$(pid_now)
	if [ "$pid" = "$new" ]; then
		"$here/mcp.sh" grid_run '{"operation":"v/ops/tau/info"}'
		exit 0
	fi
	alive "$new" || {
		echo "The new Tau (process $new) has stopped${before:+; the old one was $before${pid:+ and is still answering}}: check its log." >&2
		exit 1
	}
	i=$((i + 1))
	timeout 3 tail -f /dev/null 2>/dev/null
done
echo "The new Tau (process $new) did not answer within five minutes: check its log." >&2
exit 1
