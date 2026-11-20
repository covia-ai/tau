#!/bin/sh
# Relaunches Tau into a jar with the venue operator login, waits for the new
# process, and prints its info. Tau starts its successor itself, so it
# outlives this session.
#   relaunch.sh [jar]   jar: file://code/tau/target/tau.jar by default;
#                       file://code/last-good.jar is the rollback
# Needs ~/.tau/operator-token.json (Settings > Access: keep a venue operator
# login for local tools). The relaunch is a recorded job of the venue's.
here=$(dirname "$0")
jar="${1:-file://code/tau/target/tau.jar}"
home="${TAU_HOME:-$HOME/.tau}"
[ -f "$home/operator-token.json" ] || {
	echo "No operator login at $home/operator-token.json: tick 'Keep a venue operator login for local tools'" \
		"in Settings > Access, or relaunch from Tau's window." >&2
	exit 3
}

pid_now() {
	AS=operator "$here/mcp.sh" grid_run '{"operation":"v/ops/tau/info"}' 2>/dev/null \
		| python -I -c "import json,sys;print(json.loads(sys.stdin.read()).get('pid',''))" 2>/dev/null
}

before=$(pid_now)
[ -n "$before" ] || { echo "Tau is not answering, so it cannot relaunch itself" >&2; exit 2; }
request=$(python -I -c 'import json,sys; print(json.dumps({"operation": "v/ops/tau/relaunch", "input": {"jar": sys.argv[1]}}))' "$jar")
# The answer comes before the handover; the call may also be cut off by it.
out=$(AS=operator "$here/mcp.sh" grid_run "$request") || {
	case "$out" in
		*refused*|*Refused*|*denied*|*"not Tau"*) printf '%s\n' "$out"; echo "Tau refused the relaunch" >&2; exit 1 ;;
	esac
}
printf '%s\n' "$out"

# The old process answers, then stops; the newcomer takes over the store and
# writes fresh logins. Wait for a different pid (up to five minutes).
i=0
while [ $i -lt 100 ]; do
	pid=$(pid_now)
	if [ -n "$pid" ] && [ "$pid" != "$before" ]; then
		AS=operator "$here/mcp.sh" grid_run '{"operation":"v/ops/tau/info"}'
		exit 0
	fi
	i=$((i + 1))
	timeout 3 tail -f /dev/null 2>/dev/null
done
echo "Tau did not come back from the relaunch (old pid $before): check its log." >&2
exit 1
