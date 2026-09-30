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
#   ./run.sh run HelloWorld           同上，用于"类名不带点号"的情况（默认包里的类）
#   ./run.sh sbt "runMain learn.A" "runMain learn.B"   原样执行任意 sbt 命令
#   ./run.sh help                     打印这份用法说明
#
# 约定：参数不带点号 -> 当成 sbt 任务名原样执行；
#       参数带点号   -> 当成主类的全限定名（包名.对象名）；
#                      类名不带点号时（文件里没写 package）用 `run <类名>` 这种写法。
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

# 用法错误统一从这儿退出：状态码 2 表示"参数不对"，
# 和子命令自己失败（比如编译不过）区分开。
die() {
  echo "run.sh: $*" >&2
  exit 2
}

# 主类名先记在这儿，非空的话统一翻译成 runMain
main_class=""

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
    # 这些子命令不吃参数，多给了就报错，免得打错字悄悄跑成别的东西
    [ "$#" -eq 0 ] || die "'$action' 后面不能再跟参数"
    set -- "$action"
    ;;
  mains | list)
    # 问 sbt 要"发现了哪些入口"，就是 sbt 自己 run 时会列出来的那份清单。
    # 顺带作用：编译不过时这条命令会把编译错误报出来，比等 runMain 报错更早发现。
    [ "$#" -eq 0 ] || die "'$action' 后面不能再跟参数"
    set -- "show discoveredMainClasses"
    ;;
  help | -h | --help)
    # 把文件开头那段注释当帮助打印：从第 2 行起，到 set -euo pipefail 为止
    awk 'NR == 1 { next } /^set -euo pipefail/ { exit } /^#/ { sub(/^# ?/, ""); print }' "$0"
    exit 0
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
    [ "$#" -gt 0 ] || die 'sbt 后面要跟至少一条命令，例如 ./run.sh sbt "runMain learn.A"'
    ;;
  run)
    # 显式写法：下一个参数就是主类名。主要给"默认包里的类"用，
    # 它们没有包名，也就没有点号，没法走下面的 *.* 分支。
    [ "$#" -gt 0 ] || die "run 后面要跟主类名，例如 ./run.sh run HelloWorld"
    main_class=$1
    shift
    ;;
  *.*)
    # 带点号：当成主类的全限定名
    main_class=$action
    ;;
  *)
    echo "run.sh: 不认识 '$action'。" >&2
    echo "run.sh: 子命令有 compile / clean / console / help / mains / test / sbt；" >&2
    echo "run.sh: 要跑 main 请写全限定名（例如 learn.MyFirst）；" >&2
    echo "run.sh: 类名不含包名时改用 ./run.sh run <类名>。" >&2
    exit 2
    ;;
esac

# 编译并运行这个主类，剩下的参数原样传给 main
if [ -n "$main_class" ]; then
  sbt_command="runMain $main_class"
  if [ "$#" -gt 0 ]; then
    sbt_command="$sbt_command $*"
  fi
  set -- "$sbt_command"
fi

cd -- "$project_dir"
if [ "$#" -gt 0 ]; then
  echo "[run] sbt $*"
fi
# 首次运行会下载 Chisel / Scala 依赖，之后走本地缓存
exec java -Xmx2G -jar "$sbt_launch" "$@"
