#!/usr/bin/env bash
# 把 v0 的 Chisel 源码生成 Verilog，发布到提交仓库（默认 ../CPU-submit）。
#
# 用法:
#   scripts/publish.sh               # 生成 + 复制 + 维护 filelist，不提交
#   scripts/publish.sh --dry-run     # 只报告会做什么，不写任何文件
#   scripts/publish.sh --commit      # 额外在提交仓库里提交这两个文件
#
# 为什么要有这个脚本：OJ 要的是「框架仓库 + 生成的 RTL + filelist 条目」，
# 而我们的 Chisel 源码在另一个仓库里。手工拷贝容易漏、也难复盘，
# 所以把这段搬运固化下来，并且把源提交 hash 记进提交信息。
#
# 环境变量:
#   CPU_SUBMIT_DIR  提交仓库路径，默认 <本仓库同级>/CPU-submit
set -euo pipefail

scripts_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=env.sh
source "$scripts_dir/env.sh"

project=v0
main_class=cpu.Generate
out_rel="$project/build/generated"
rtl_name=student_top.sv
generated_rel=verilog/generated/$rtl_name
filelist_rel=verilog/filelist.f

dry_run=0
do_commit=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=1 ;;
    --commit)  do_commit=1 ;;
    *) echo "用法: $0 [--dry-run] [--commit]" >&2; exit 2 ;;
  esac
done

submit=${CPU_SUBMIT_DIR:-"$MY_ROOT/../CPU-submit"}
if [ ! -d "$submit/.git" ]; then
  echo "publish.sh: 找不到提交仓库 $submit（可以用 CPU_SUBMIT_DIR 指定）" >&2
  exit 2
fi
if [ ! -f "$submit/$filelist_rel" ]; then
  echo "publish.sh: $submit 不像助教框架仓库（缺 $filelist_rel）" >&2
  exit 2
fi

# 1) Chisel -> SystemVerilog
"$scripts_dir/gen.sh" "$project" "$main_class" "$out_rel"
rtl="$MY_ROOT/$out_rel/$rtl_name"
if [ ! -f "$rtl" ]; then
  echo "publish.sh: 生成物不存在：$rtl" >&2
  exit 1
fi

dest="$submit/$generated_rel"
if [ -f "$dest" ] && cmp -s -- "$rtl" "$dest"; then
  echo "[publish] 生成物与提交仓库里的一致，无需复制"
elif [ "$dry_run" = 1 ]; then
  echo "[dry-run] 会写入 $dest"
else
  mkdir -p -- "$(dirname -- "$dest")"
  cp -- "$rtl" "$dest"
  echo "[publish] 写入 $dest"
fi

# 2) 维护 filelist：只在缺少条目时追加一行，绝不动其它内容
filelist="$submit/$filelist_rel"
if grep -qE "^[[:space:]]*generated/$rtl_name([[:space:]]|$)" "$filelist"; then
  echo "[publish] filelist 里已有 generated/$rtl_name"
elif [ "$dry_run" = 1 ]; then
  echo "[dry-run] 会向 $filelist 追加一行 generated/$rtl_name"
else
  # 上一行没有换行时先补一个，避免把新条目粘在旧内容后面
  [ -s "$filelist" ] && [ "$(tail -c1 -- "$filelist" | wc -l)" -eq 0 ] && printf '\n' >> "$filelist"
  printf 'generated/%s\n' "$rtl_name" >> "$filelist"
  echo "[publish] 向 filelist 追加 generated/$rtl_name"
fi

# 3) 记下来源版本，便于复盘本次提交对应哪份 Chisel 源码
source_hash=$(git -C "$MY_ROOT" rev-parse --short HEAD)
message="Generate Verilog from CPU0@$source_hash"

if [ "$dry_run" = 1 ]; then
  echo "[dry-run] 建议的提交信息：$message"
  exit 0
fi

if [ "$do_commit" = 1 ]; then
  # 只 add 这两个文件：提交仓库里可能有别的改动（例如你自己在改 README）
  git -C "$submit" add -- "$generated_rel" "$filelist_rel"
  if git -C "$submit" diff --cached --quiet; then
    echo "[publish] 没有需要提交的改动"
  else
    git -C "$submit" commit -m "$message"
    echo "[publish] 已在提交仓库提交：$message（别忘了 git push）"
  fi
else
  echo "[publish] 建议的提交信息：$message"
  git -C "$submit" status --short -- "$generated_rel" "$filelist_rel" || true
fi
