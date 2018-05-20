#!/usr/bin/env bash

# determine physical directory of this script
src="${BASH_SOURCE[0]}"
while [ -L "$src" ]; do
  dir="$(cd -P "$(dirname "$src")" && pwd)"
  src="$(readlink "$src")"
  [[ $src != /* ]] && src="$dir/$src"
done
MYDIR="$(cd -P "$(dirname "$src")" && pwd)"

function cleanup {
  kill -9 $DVIS_PID
  kill -9 $RAFT_PID
}

trap cleanup ERR
trap cleanup SIGINT

cd "$MYDIR"

java -jar ./dviz/target/dviz.jar &
DVIS_PID="$!"
sleep 10

python ./pydviz/raft.py &
RAFT_PID="$!"
sleep 5

open "http://localhost:3000"

wait
