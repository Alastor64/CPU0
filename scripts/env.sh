#!/usr/bin/env bash
# 课程硬件工具链的环境变量。在 WSL 里用 source 加载：
#
#   source scripts/env.sh
#
# 这里设置的变量含义与助教框架一致，所以加载之后：
#   * 本仓库的 gen.sh / sim.sh / synth.sh 能找到 verilator / yosys / sta；
#   * 在 RISC-V-CPU-2026 里直接跑 make build/test/perf/synth 也能用同一套工具，
#     不需要把 AppImage 文件放进框架目录。
#
#   CPU2026_APPDIR      解包后的工具链根目录（AppImage 的解包产物）
#   CPU2026_ASAP7_LIB   ASAP7 标准单元库目录（正好 5 个 .lib）
#   SBT_LAUNCH          sbt 启动器 jar（WSL 里没有独立 sbt，用它启动）

# 本文件是被 source 的，$0 指向调用者，因此用 BASH_SOURCE 定位自己的位置
_my_scripts_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
MY_ROOT=$(cd -- "$_my_scripts_dir/.." && pwd)
unset _my_scripts_dir

export CPU2026_APPDIR="$MY_ROOT/tools/cpu2026"
export CPU2026_ASAP7_LIB="$CPU2026_APPDIR/asap7/lib"
export SBT_LAUNCH="$MY_ROOT/tools/sbt-launch.jar"

# PATH 里避免重复追加（重复 source 时）
case ":$PATH:" in
  *":$CPU2026_APPDIR/bin:"*) ;;
  *) export PATH="$CPU2026_APPDIR/bin:$PATH" ;;
esac

if [ ! -x "$CPU2026_APPDIR/bin/verilator" ]; then
  echo "env.sh: 找不到工具链目录 $CPU2026_APPDIR" >&2
  echo "env.sh: 请先执行 scripts/setup-tools.sh 解包 AppImage" >&2
  return 1 2>/dev/null || exit 1
fi
