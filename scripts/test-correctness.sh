#!/usr/bin/env bash
# 生成或复用当前 CPU 的仿真器，并运行 correctness 用例。
#
# 默认行为:
#   - 跳过 correctness_qsort 和 correctness_pi
#   - MAX_CYCLES=12000000
#   - 仅当仿真器不存在时重新生成和编译
#
# 用法:
#   scripts/test-correctness.sh
#   scripts/test-correctness.sh --rebuild
#   scripts/test-correctness.sh --case correctness_hanoi
#   scripts/test-correctness.sh --directed path/to/testcases
#   scripts/test-correctness.sh --include-slow
#   scripts/test-correctness.sh --max-cycles 24000000
#   scripts/test-correctness.sh --latency 8
#   SIM=/path/to/sim scripts/test-correctness.sh
#
# 环境变量:
#   RISC_V_CPU_2026_DIR  课程框架目录，默认同级目录 RISC-V-CPU-2026
#   SIM                  已构建仿真器，默认 v0/build/correctness-sim/sim
#   MAX_CYCLES           用例周期上限，默认 12000000
#   LATENCY              测试台响应延迟，默认 10
#   JOBS                 编译并行任务数，默认 4
set -euo pipefail

scripts_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$scripts_dir/.." && pwd)
framework=${RISC_V_CPU_2026_DIR:-"$repo_root/../RISC-V-CPU-2026"}

generated_rel=v0/build/generated-correctness
generated_dir="$repo_root/$generated_rel"
build_dir="$repo_root/v0/build/correctness-sim"
filelist="$generated_dir/filelist.f"
sim=${SIM:-"$build_dir/sim"}

max_cycles=${MAX_CYCLES:-12000000}
latency=${LATENCY:-10}
jobs=${JOBS:-4}
rebuild=0
include_slow=0
case_name=
directed_dir=

usage() {
  sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --rebuild)
      rebuild=1
      shift
      ;;
    --include-slow)
      include_slow=1
      shift
      ;;
    --case)
      [ "$#" -ge 2 ] || { echo "--case requires a test name" >&2; exit 2; }
      case_name=$2
      shift 2
      ;;
    --max-cycles)
      [ "$#" -ge 2 ] || { echo "--max-cycles requires a value" >&2; exit 2; }
      max_cycles=$2
      shift 2
      ;;
    --latency)
      [ "$#" -ge 2 ] || { echo "--latency requires a value" >&2; exit 2; }
      latency=$2
      shift 2
      ;;
    --directed)
      [ "$#" -ge 2 ] || { echo "--directed requires a directory" >&2; exit 2; }
      directed_dir=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ ! -d "$framework/testcases" ]; then
  echo "framework testcases not found: $framework/testcases" >&2
  exit 2
fi

if [ "$rebuild" -eq 1 ] || [ ! -x "$sim" ]; then
  "$scripts_dir/gen.sh" v0 cpu.generate.Generate "$generated_rel"
  printf 'student_top.sv\n' > "$filelist"
  make -C "$framework" build \
    FILELIST="$filelist" \
    BUILD="$build_dir" \
    JOBS="$jobs"
  sim="$build_dir/sim"
fi

failed=()
passed=0

run_case() {
  local name=$1
  echo "===== $name ====="
  if python3 -u "$framework/scripts/testcase.py" \
    --kind correctness \
    --testcases "$framework/testcases" \
    --max-cycles "$max_cycles" \
    --latency "$latency" \
    --sim "$sim" \
    --case "$name"; then
    passed=$((passed + 1))
  else
    failed+=("$name")
  fi
}

if [ -n "$case_name" ]; then
  run_case "$case_name"
else
  for testcase in "$framework"/testcases/correctness_*; do
    [ -d "$testcase" ] || continue
    name=$(basename "$testcase")
    if [ "$include_slow" -eq 0 ]; then
      case "$name" in
        correctness_qsort|correctness_pi)
          echo "===== skip $name ====="
          continue
          ;;
      esac
    fi
    run_case "$name"
  done
fi

if [ -n "$directed_dir" ]; then
  echo "===== directed tests: $directed_dir ====="
  if python3 -u "$framework/scripts/testcase.py" \
    --kind correctness \
    --testcases "$directed_dir" \
    --max-cycles "$max_cycles" \
    --latency "$latency" \
    --sim "$sim"; then
    passed=$((passed + 1))
  else
    failed+=("$directed_dir")
  fi
fi

echo "===== summary ====="
echo "passed=$passed failed=${#failed[@]}"
if [ "${#failed[@]}" -ne 0 ]; then
  printf 'failed cases: %s\n' "${failed[*]}"
  exit 1
fi
echo "ALL PASSED"
