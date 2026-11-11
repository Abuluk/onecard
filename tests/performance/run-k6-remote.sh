#!/bin/sh

set -u
umask 077

k6_binary=$1
virtual_users=$2
duration=$3
base_url=$4
summary_path=$5
stdout_path=$6
stderr_path=$7
exit_path=$8
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

trap 'rm -f "$script_dir/k6.env"' EXIT
set -a
. "$script_dir/k6.env"
set +a
export BASE_URL="$base_url"
export VUS="$virtual_users"
export DURATION="$duration"

set +e
"$k6_binary" run --summary-export "$summary_path" "$script_dir/gateway-platform.js" \
  >"$stdout_path" 2>"$stderr_path"
status=$?
printf '%s\n' "$status" >"$exit_path"
exit "$status"
