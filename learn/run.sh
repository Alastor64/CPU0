#!/usr/bin/env bash
# learn 工程的快捷入口：把常用的 sbt 命令包成一条命令。
# 原理、等价的原命令、以及为什么建议用 runMain，见同目录的 RUN.md。
#
# 用法（在 WSL 里执行；脚本放在任何目录下都能调用，它会自己切到 learn 工程）:
#
#   ./run.sh                          进入 sbt 交互模式
#                                     （提示符下用 ~runMain 可以"改一行就重跑"）
#   ./run.sh compile                  只编译
#   ./run.sh clean                    删掉编译产物
#   ./run.sh console                  打开带本工程 classpath 的 Scala REPL
#   ./run.sh mains                    列出工程里所有能跑的 main
#   ./run.sh test                     跑 src/test/scala 下的全部测试
#   ./run.sh test mylib.BitsSpec      只跑指定的测试类（可以写多个）
#   ./run.sh learn.MyFirst            编译并运行这个 main（= sbt runMain）
#   ./run.sh learn.MyFirst hello 42   运行 main，并把 hello、42 传给它
#   ./run.sh sbt "runMain learn.A" "runMain learn.B"   原样执行任意 sbt 命令
#
# 约定：参数不带点号 -> 当成 sbt 任务名原样执行；
#       参数带点号   -> 当成主类的全限定名（包名.对象名）。
set -euo pipefail

# 工程目录 = 本脚本所在目录；sbt 启动器在仓库根的 tools/ 下
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
sbt_launch="$project_dir/../tools/sbt-launch.jar"

if [ ! -f "$sbt_launch" ]; then
  echo "run.sh: 找不到 sbt 启动器 $sbt_launch" >&2
  exit 2
fi

action=${1:-}
if [ "$#" -gt 0 ]; then shift; fi

case "$action" in
  "")
    # 不带参数：留给 sbt 自己进交互模式。
    # 但标准输入不是终端时（脚本里、重定向时）sbt 会空转刷提示符，这里提前拦住。
    if [ ! -t 0 ]; then
      echo "run.sh: 交互模式需要有终端；要非交互地跑，请给个子命令或主类名，例如：" >&2
      echo "run.sh:   ./run.sh compile" >&2
      echo "run.sh:   ./run.sh learn.MyFirst" >&2
      exit 2
    fi
    set --
    ;;
  compile | clean | console | update)
    set -- "$action"
    ;;
  mains | list)
    # 问 sbt 要"发现了哪些入口"，就是 sbt 自己 run 时会列出来的那份清单。
    # 顺带作用：编译不过时这条命令会把编译错误报出来，比等 runMain 报错更早发现。
    set -- "show discoveredMainClasses"
    ;;
  test)
    # 带类名时只跑这些类，不带就跑全部
    if [ "$#" -gt 0 ]; then
      set -- "testOnly $*"
    else
      set -- test
    fi
    ;;
  sbt)
    # 逃生舱：后面的参数全部原样当 sbt 命令
    if [ "$#" -eq 0 ]; then
      echo "run.sh: sbt 后面要跟至少一条命令，例如 ./run.sh sbt \"runMain learn.A\"" >&2
      exit 2
    fi
    ;;
  *.*)
    # 主类名：编译并运行它，剩下的参数原样传给 main
    sbt_command="runMain $action"
    if [ "$#" -gt 0 ]; then
      sbt_command="$sbt_command $*"
    fi
    set -- "$sbt_command"
    ;;
  *)
    echo "run.sh: 不认识 '$action'。" >&2
    echo "run.sh: 子命令有 compile / clean / console / mains / test / sbt；" >&2
    echo "run.sh: 要跑 main 请写全限定名（带包名，例如 learn.MyFirst）。" >&2
    exit 2
    ;;
esac

cd -- "$project_dir"
if [ "$#" -gt 0 ]; then
  echo "[run] sbt $*"
fi
# 首次运行会下载 Chisel / Scala 依赖，之后走本地缓存
exec java -Xmx2G -jar "$sbt_launch" "$@"
