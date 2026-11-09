#!/usr/bin/env bash
# Two-client mode (coopsim-1109): one HOST client and one GUEST client run the same scenario filter at once and meet
# through dev.testkit.gametest.coop.CoopSync. The host starts the sim in its integrated server and publishes it on the
# LAN port; the guest (offline name TkGuest) joins 127.0.0.1:<port>.
#
#   bash run-coop.sh [-s SCENARIO] [-j JAR] [-m 26.3|26.2] [-p PORT] [-t MINUTES] [-g GUEST_NAME]
#
# SCENARIO defaults to 1109-coop-two. The guest runs in a sibling worktree <this checkout>-coopguest (created on first
# use, moved to this checkout's HEAD every run: the guest runs COMMITTED code, so commit before a run). Both clients
# take one clientslot ticket of 2, asked from this script's own process. Each side writes its own report
# (build/testkit-report/summary.md in each checkout); this prints both and exits 0 only when both passed.
set -u
here=$(cd "$(dirname "$0")" && pwd -W)
scen="1109-coop-two"; jar=""; mc="26.3"; port=25870; mins=15; gname="TkGuest"
while getopts "s:j:m:p:t:g:" o; do
  case $o in
    s) scen=$OPTARG ;; j) jar=$OPTARG ;; m) mc=$OPTARG ;; p) port=$OPTARG ;; t) mins=$OPTARG ;; g) gname=$OPTARG ;;
    *) echo "usage: bash run-coop.sh [-s SCENARIO] [-j JAR] [-m MC] [-p PORT] [-t MINUTES] [-g GUEST_NAME]"; exit 4 ;;
  esac
done

# A free LAN port (the host binds every interface on it).
for i in $(seq 0 19); do
  if ! netstat -ano | grep -q "[:.]$((port + i)) .*LISTENING"; then port=$((port + i)); break; fi
done

guest="${here}-coopguest"
head=$(git -C "$here" rev-parse HEAD)
if [ -n "$(git -C "$here" status --porcelain --untracked-files=no)" ]; then
  echo "WARNING: $here has uncommitted changes; the guest runs committed HEAD ${head:0:9} only"
fi
if [ ! -d "$guest" ]; then
  git -C "$here" worktree add --detach "$guest" "$head" > /dev/null 2>&1 || { echo "could not create $guest"; exit 4; }
else
  if [ -n "$(git -C "$guest" status --porcelain --untracked-files=no)" ]; then echo "$guest has uncommitted changes"; exit 4; fi
  git -C "$guest" checkout --detach "$head" > /dev/null 2>&1 || { echo "could not move $guest to $head"; exit 4; }
fi

if [ -z "$jar" ]; then
  jar=$(powershell -NoProfile -ExecutionPolicy Bypass -Command ". '$here/tools/testkit-config.ps1'; \$c = Get-TestkitConfig '$here'; Find-TestkitSnapshotJar \$c '$mc'" < /dev/null | tr -d '\r' | tail -1)
fi
[ -f "$jar" ] || { echo "no mod jar ($jar); pass -j"; exit 4; }

dir="$here/build/coop-$(date +%s)"
mkdir -p "$dir"
echo "coop run: $scen, MC $mc, jar $jar, LAN port $port, guest $gname in $guest, sync $dir"

bash /c/Users/Hunter/km-scripts/clientslot.sh 2 "$mins"

secs=$((mins * 60))
run() { # checkout role extra-args log
  local cmd="& '$1/run-scenario.ps1' -Scenario '$scen' -ModUnderTest '$jar' -Minecraft '$mc' -TimeoutSeconds $secs -Extra $3; exit \$LASTEXITCODE"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$cmd" < /dev/null > "$4" 2>&1
}
common="'-PcoopDir=$dir','-PcoopPort=$port'"
run "$here" host "'-PcoopRole=host',$common" "$dir/host.log" &
hp=$!
run "$guest" guest "'-PcoopRole=guest',$common,'-Pusername=$gname'" "$dir/guest.log" &
gp=$!
wait $hp; he=$?
wait $gp; ge=$?

for side in host guest; do
  echo "== $side (exit $([ $side = host ] && echo $he || echo $ge))"
  grep -aE "^\[(coop|1109-coop[^]]*)\] |\] (PASS|FAIL)$" "$dir/$side.log" | grep -av "wrote" | cut -c1-300 | tail -25
done
for c in "$here" "$guest"; do
  echo "== $c/build/testkit-report/summary.md"; grep -aE "^\| |totals" "$c/build/testkit-report/summary.md" 2>/dev/null | head -8
done
[ $he -eq 0 ] && [ $ge -eq 0 ]
