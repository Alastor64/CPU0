#!/usr/bin/env bash
# Chisel 源码 -> SystemVerilog。
#
# 用法:
#   scripts/gen.sh <工程目录> <主类> [输出目录]
#
# 例:
#   scripts/gen.sh learn learn.Generate learn/build/generated
#
# 输出目录相对于仓库根目录；省略时用 <工程目录>/build/generated。
# 具体导出哪些模块由主类决定（见 learn/src/main/scala/learn/Generate.scala）。
set -euo pipefail

scripts_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=env.sh
source "$scripts_dir/env.sh"

if [ "$#" -lt 2 ]; then
  echo "用法: $0 <工程目录> <主类> [输出目录]" >&2
  exit 2
fi

project=$1
main_class=$2
out=${3:-build/generated}

if [ ! -d "$MY_ROOT/$project" ]; then
  echo "gen.sh: 找不到工程目录 $project" >&2
  exit 2
fi
if [ ! -f "$SBT_LAUNCH" ]; then
  echo "gen.sh: 找不到 sbt 启动器 $SBT_LAUNCH" >&2
  echo "gen.sh: 需要 tools/sbt-launch.jar（见 tools/sbt.cmd 里的下载说明）" >&2
  exit 2
fi

# 为了让命令在任何目录下都好写，输出目录统一按「仓库根目录」来解释：
# 相对路径先补成绝对路径，再切进工程目录运行 sbt。
case "$out" in
  /*) ;;
  *) out="$MY_ROOT/$out" ;;
esac

cd -- "$MY_ROOT/$project"
echo "[gen] $project / $main_class -> $out"
# sbt 首次运行会下载 Chisel、Scala 与编译器插件，之后走本地缓存
exec java -Xmx2G -jar "$SBT_LAUNCH" "runMain $main_class $out"
