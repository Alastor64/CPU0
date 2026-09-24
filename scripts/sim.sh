#!/usr/bin/env bash
# 用 Verilator 编译「设计 + 测试台」并运行。
#
# 用法:
#   scripts/sim.sh <顶层模块名> <输出目录> <文件...>
#
# 例:
#   scripts/sim.sh tb_counter learn/build/sim_counter \
#       learn/build/generated/counter_w8.sv learn/tb/tb_counter.sv
#
# 顶层模块是测试台（自带 initial 块），Verilator 会为它自动生成 main。
# 每次都会清掉 obj 目录重新编译：本仓库在 Windows 盘（/mnt/d）上，
# 文件时间戳精度会导致 make 误判「目标已是最新」，从而跑出旧的仿真器。
set -euo pipefail

scripts_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=env.sh
source "$scripts_dir/env.sh"

if [ "$#" -lt 3 ]; then
  echo "用法: $0 <顶层模块名> <输出目录> <文件...>" >&2
  exit 2
fi

top=$1
out=$2
shift 2
files=("$@")

# Verilator 解析源文件路径时是相对它自己的中间目录（-Mdir）的，
# 所以这里先把所有输入文件转成绝对路径，免得"文件明明在却找不到"。
for i in "${!files[@]}"; do
  files[$i]=$(realpath -- "${files[$i]}")
done

mkdir -p -- "$out"
# 转成绝对路径：Verilator 生成的 Makefile 在 obj 目录里执行，
# 相对路径会被解释成相对 obj 目录，二进制就跑到别处去了。
out=$(cd -- "$out" && pwd)
rm -rf -- "$out/obj" "$out/sim"

echo "[sim] Verilator 编译 $top"
verilator --binary --timing -Wall -Wno-fatal -j "$(nproc)" \
  --top-module "$top" -Mdir "$out/obj" -o "$out/sim" "${files[@]}"

echo "[sim] 运行 $out/sim"
exec "$out/sim"
