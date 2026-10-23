#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
/usr/bin/python3 "$project_root/tools/verify_platform_resources.py"
