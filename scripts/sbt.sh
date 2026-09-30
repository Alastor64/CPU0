#!/usr/bin/env bash
# 在 WSL 里当「Linux 原生 sbt」用：直接跑仓库自带的启动器，参数原样转发。
#
# 用法和真正的 sbt 完全一样：
#   scripts/sbt.sh compile
#   scripts/sbt.sh -Dbloop.export-meta-build=true bloopInstall
#
# 为什么需要它：WSL 的 PATH 里会混进 Windows 侧的工具（比如 scoop 装的 sbt）。
# 编辑器（Metals）若找到那个 sbt，就会用 Windows 的 JDK 去解析依赖，生成一堆
# C:\... 路径的 bloop 配置；WSL 侧的 bloop 读不了这些路径，就表现为
# 「no build target found」。把它配给 Metals 的 metals.sbtScript 即可。
set -euo pipefail

scripts_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=env.sh
source "$scripts_dir/env.sh"

if [ ! -f "$SBT_LAUNCH" ]; then
  echo "sbt.sh: 找不到 sbt 启动器 $SBT_LAUNCH" >&2
  exit 2
fi

# 用 exec 让 sbt 直接顶替本进程：调用方（Metals、脚本）发的信号才能传到它。
exec java -Xmx2G -jar "$SBT_LAUNCH" "$@"
