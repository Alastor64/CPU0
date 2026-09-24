#!/usr/bin/env bash
# 把课程工具链 AppImage 解包成一个普通目录（tools/cpu2026）。
#
# 用法:
#   scripts/setup-tools.sh [AppImage 路径]
#
# 为什么要解包而不是直接跑 AppImage：
#   * AppImage 挂载需要 FUSE2，而本机只有 FUSE3，每次运行都要走解包模式；
#   * 解包一次后就是普通目录，启动快、可重复、不依赖 FUSE；
#   * 解包产物自带私有动态链接器和运行库，不碰系统里的任何东西。
#
# 不指定路径时，会依次在这些位置找 cpu2026-tools-x86_64.AppImage：
#   tools/ 目录、仓库上一级目录、助教框架目录。
set -euo pipefail

scripts_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
my_root=$(cd -- "$scripts_dir/.." && pwd)
target="$my_root/tools/cpu2026"

appimage=${1:-}
if [ -z "$appimage" ]; then
  for candidate in \
    "$my_root/tools/cpu2026-tools-x86_64.AppImage" \
    "$my_root/../cpu2026-tools-x86_64.AppImage" \
    "$my_root/../RISC-V-CPU-2026/cpu2026-tools-x86_64.AppImage"
  do
    if [ -f "$candidate" ]; then appimage=$candidate; break; fi
  done
fi
if [ -z "$appimage" ] || [ ! -f "$appimage" ]; then
  echo "setup-tools.sh: 找不到 cpu2026-tools-x86_64.AppImage" >&2
  echo "setup-tools.sh: 用法: $0 <AppImage 路径>" >&2
  exit 2
fi

if [ -x "$target/bin/verilator" ] && [ -f "$target/versions.txt" ]; then
  echo "工具链已就绪：$target"
  cat "$target/versions.txt"
  exit 0
fi

# 解包必须在目标目录的父目录里进行：AppImage 会生成 squashfs-root
stage="$my_root/tools/.extract"
rm -rf -- "$stage"
mkdir -p -- "$stage"
echo "[setup] 解包 $appimage"
(cd "$stage" && "$appimage" --appimage-extract >/dev/null)
if [ ! -d "$stage/squashfs-root" ]; then
  echo "setup-tools.sh: 解包失败（没有生成 squashfs-root）" >&2
  exit 1
fi
rm -rf -- "$target"
mv -- "$stage/squashfs-root" "$target"
rm -rf -- "$stage"

echo "[setup] 校验"
"$target/bin/verilator" --version
"$target/bin/yosys" -V
"$target/bin/sta" -version
libs=$(find "$target/asap7/lib" -maxdepth 1 -name '*.lib' | wc -l)
echo "[setup] ASAP7 库: $libs 个（应为 5）"
if [ "$libs" -ne 5 ]; then
  echo "setup-tools.sh: ASAP7 库数量不对，综合流程会失败" >&2
  exit 1
fi
echo "[setup] 完成：$target"
