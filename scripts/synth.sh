#!/usr/bin/env bash
# 用 Yosys（+ ASAP7 标准单元库）估算面积，用 OpenSTA 估算最高频率。
#
# 用法:
#   scripts/synth.sh <顶层模块名> <输出目录> <文件...>
#
# 例:
#   scripts/synth.sh counter_w8 learn/build/synth_counter \
#       learn/build/generated/counter_w8.sv
#
# 可调环境变量:
#   CLOCK_PERIOD_NS    目标时钟周期，默认 2.0 ns（即 500 MHz）
#   CLOCK_PORT         时钟端口名，默认 clock
#   CPU2026_FRAMEWORK  助教框架目录，默认同级目录 RISC-V-CPU-2026
#                      （只用它里面的 timing.tcl 做时序分析，不改动它）
#
# 流程和助教框架的 make synth 是一致的（同一批库、同样的映射步骤），
# 区别只在于：这里不处理 fakeram，也允许顶层模块叫任意名字。
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

# 统一用绝对路径：综合脚本本身会切到输出目录里去跑 OpenSTA
for i in "${!files[@]}"; do
  files[$i]=$(realpath -- "${files[$i]}")
done

clock_period_ns=${CLOCK_PERIOD_NS:-2.0}
clock_port=${CLOCK_PORT:-clock}
framework=${CPU2026_FRAMEWORK:-"$MY_ROOT/../RISC-V-CPU-2026"}

mkdir -p -- "$out"
out=$(cd -- "$out" && pwd)
rm -rf -- "$out"/*

# ASAP7 五个 RVT TT 库 + 对应的 liberty 参数（Yosys 的 abc / stat 都要用）
libs=("$CPU2026_ASAP7_LIB"/*.lib)
if [ "${#libs[@]}" -ne 5 ]; then
  echo "synth.sh: $CPU2026_ASAP7_LIB 下应有 5 个 .lib，实际 ${#libs[@]} 个" >&2
  exit 1
fi
liberty_args=""
for lib in "${libs[@]}"; do liberty_args+=" -liberty \"$lib\""; done
# 时序单元库单独取出来，dfflibmap 只认它
seq_lib=$(printf '%s\n' "${libs[@]}" | grep '_SEQ_' || true)
if [ -z "$seq_lib" ]; then
  echo "synth.sh: ASAP7 库里没找到 _SEQ_ 时序单元库" >&2
  exit 1
fi
period_ps=$(python3 -c "print(f'{$clock_period_ns * 1000:.9g}')")

echo "[synth] Yosys 综合 $top（目标周期 ${clock_period_ns} ns）"
cat > "$out/synth.ys" <<EOF
# 读入设计
$(printf 'read_verilog -sv "%s"\n' "${files[@]}")
# 先把标准单元库声明成黑盒模块：Yosys 需要知道每个引脚的方向
# （ASAP7 的触发器输出是反相的 QN，不声明方向就会被判成"输出悬空"）
$(printf 'read_liberty -lib -ignore_miss_func "%s"\n' "${libs[@]}")
# 注意：模块名不能加引号，Yosys 的脚本解析器只在文件路径上去掉引号
hierarchy -check -top $top
# -noabc：先不做通用门级映射，把工艺映射留给下面那次带 liberty 的 abc，
# 这和助教框架的做法一致（否则会出现两次映射，导致输出端口失去驱动）
synth -top $top -flatten -noabc
check -assert
# 综合出的网表里不允许有 latch（本流程不支持，出现即设计有问题）
select -assert-none t:\$dlatch* t:\$_DLATCH*
dfflibmap -liberty "$seq_lib"
abc -exe "$CPU2026_APPDIR/bin/yosys-abc" $liberty_args -D $period_ps
clean
clean -purge
hilomap -hicell TIEHIx1_ASAP7_75t_R H -locell TIELOx1_ASAP7_75t_R L
check -assert -mapped
tee -o "$out/stat.json" stat -json $liberty_args
write_json "$out/design.json"
write_verilog -noattr -noexpr "$out/mapped.v"
EOF
yosys -Q -T -q -l "$out/synth.log" -s "$out/synth.ys"

echo "[synth] 面积统计"
python3 - "$out/stat.json" <<'PY'
import json, sys
design = json.load(open(sys.argv[1]))["design"]
print(f"  总面积:   {design['area']:10.3f} um^2")
print(f"  标准单元: {design['num_cells']:10d} 个")
PY

# 时序分析：复用助教框架的 timing.tcl（它本身与顶层模块名无关），
# 只是把 link_design 的目标换成我们自己的顶层。
timing_tcl="$framework/scripts/timing.tcl"
if [ ! -f "$timing_tcl" ]; then
  echo "[synth] 跳过时序分析：找不到 $timing_tcl" >&2
  exit 0
fi
echo "[synth] OpenSTA 时序分析"
cat > "$out/timing.ys.tcl" <<EOF
set report_dir "$out"
set clock_port "$clock_port"
set clock_period $clock_period_ns
$(printf 'read_liberty "%s"\n' "${libs[@]}")
set_cmd_units -time ns -capacitance fF
read_verilog "$out/mapped.v"
link_design "$top"
source "$timing_tcl"
EOF
# OpenSTA 的 -exit 在 Tcl 报错时也可能返回 0，所以显式包一层 catch
{
  echo "if {[catch {"
  cat "$out/timing.ys.tcl"
  echo '} message]} { puts stderr "Timing analysis failed: $message"; exit 1 }'
  echo "exit 0"
} > "$out/timing.tcl"
rm -f -- "$out/timing.ys.tcl"
(cd "$out" && sta -no_init -exit timing.tcl > timing.log 2>&1)

python3 - "$out/timing_values.json" <<'PY'
import json, sys
values = json.load(open(sys.argv[1]))
fmax = values["estimated_fmax_mhz"]
slack = values["worst_setup_slack_ns"]
if fmax is None:
    print("  最高频率: 无有效时序路径（纯组合逻辑？）")
else:
    print(f"  最高频率: {fmax:10.2f} MHz")
    print(f"  最小周期: {values['minimum_period_ns']:10.4f} ns")
if slack is not None:
    print(f"  目标周期下的建立裕量: {slack:+.4f} ns")
PY
echo "[synth] 报告: $out/stat.json, $out/timing.rpt, $out/timing.log"
