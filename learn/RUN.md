# 在 learn 里编译与运行 Scala 程序

`learn` 是一个 sbt 工程，启动器已经在仓库里（`tools/sbt-launch.jar`），**不需要额外安装**。

sbt 把 `src/main/scala/` 下的**所有**文件一起编译成一份 classpath，其中每个带 `main`
的 `object` 都是一个独立入口，运行时点名要跑哪一个。所以加新练习不用新建工程、也不用改
`build.sbt`：往 `src/main/scala/` 里放文件就行，放多少个 main 都互不影响
（只有在同一个包里出现同名定义才会冲突）。

常用命令都包在同目录的 `run.sh` 里，本文是它的完整用法；它等价的原命令见文末附录。
脚本可以放在任何目录下调用，它会自己切到 learn 工程、并找到仓库里的 sbt 启动器。

## 用法总览

| 命令 | 作用 | 等价的 sbt 命令 |
| --- | --- | --- |
| `./run.sh` | 进交互模式 | （不带参数启动 sbt） |
| `./run.sh compile` | 编译全部源码 | `compile` |
| `./run.sh clean` | 删掉编译产物 | `clean` |
| `./run.sh mains`（或 `list`） | 列出所有能跑的 main | `show discoveredMainClasses` |
| `./run.sh run <类名> [参数...]` | 运行指定的 main | `runMain <类名> [参数...]` |
| `./run.sh <包名>.<类名> [参数...]` | 同上，简写形式 | `runMain <包名>.<类名> [参数...]` |
| `./run.sh test [测试类...]` | 跑测试（全部或指定几个） | `test` / `testOnly <测试类...>` |
| `./run.sh console` | 打开 Scala REPL | `console` |
| `./run.sh sbt <命令...>` | 原样执行任意 sbt 命令 | （原样转发） |
| `./run.sh help` | 忘了就看一眼用法 | — |

一次完整的流程大致是这样：

```sh
cd /mnt/d/CPU/my/learn

./run.sh compile                   # 1. 先确认能编过
./run.sh mains                     # 2. 看有哪些入口
./run.sh learn.MyFirst hello 42    # 3. 跑起来
```

## 参数是怎么解析的

只有第一个参数会被 `run.sh` 解释，规则很短：

1. **带点号** → 当成主类的全限定名，翻译成 `runMain`；
2. **是下面这些子命令之一** → 走对应分支：
   `compile`、`clean`、`console`、`mains`、`list`、`run`、`test`、`sbt`、`help`；
3. **既没有点号又不是子命令** → 直接报错退出，不瞎猜（例如把 `compile` 打成 `compiel`）。

子命令后面多写的参数按子命令各自处理：`compile`、`clean`、`console`、`mains`
不接受多余参数，多给会报错；`run`、`test`、`sbt` 会用到后面的参数。

所有用法错误的提示都以 `run.sh: ` 开头，并且以非 0 状态退出，方便写进脚本里判断。

## 子命令详解

### `./run.sh` — 进交互模式

```sh
./run.sh
```

进到 sbt 提示符，之后可以连着敲多条命令。最有用的是 `~` 前缀：**文件一改就重新编译、重跑**。

```text
> ~runMain learn.MyFirst      # 改了 MyFirst.scala 存盘，它就自动重跑
> ~test                       # 改一行就重跑测试，写用例时很好用
> show discoveredMainClasses  # 临时看一眼有哪些入口
> exit
```

注意两点：

- 交互模式需要真的终端。标准输入被重定向时（例如写进脚本、`< /dev/null`），
  脚本会直接报错退出，免得 sbt 在那儿空转刷提示符；
- 不想要这种"守着一个进程"的用法时，用下面的一次性命令。

### `./run.sh compile` — 只编译

```sh
./run.sh compile
```

编译 `src/main/scala/` 下的全部源码，不运行任何东西。写代码时最常用它来快速看有没有编译错误。

编译产物在 `target/scala-2.13/classes`（已被 `.gitignore` 忽略，不会提交）。
第一次运行会下载 Chisel / Scala 依赖，之后走本地缓存。

```text
run.sh: 'compile' 后面不能再跟参数        # 多写参数会被拒绝，不会静默忽略
```

### `./run.sh clean` — 删掉编译产物

```sh
./run.sh clean
```

删掉 `target/` 下的产出。一般只在怀疑"编译缓存不对"时用，之后再编译会慢一些。

### `./run.sh mains` — 看有哪些 main 能跑

这是问"我有哪些程序可以跑"的正规入口，`list` 是它的同义词：

```sh
./run.sh mains
```

它问的是 sbt 的 `discoveredMainClasses`——也就是 sbt 自己 `run` 时会列出来的那份清单。
每个入口一行，输出形如：

```text
[info] * learn.Generate
[info] * tmpone.First
[info] * tmptwo.Second
```

要点：

- 一个入口要先能**编译通过**才会出现在这里。清单是空的、或者少了一个你刚加的文件时，
  多半是编译没过——`./run.sh mains` 这时会把编译错误直接报出来（它本身依赖编译结果），
  比等到 `runMain` 才发现要早；
- 名字就是全限定名（包名.对象名），可以直接拿去做 `./run.sh run <类名>` 或 `./run.sh <全限定名>`；
- 名字排在前面的是包名靠前的，同一个包里按类名字母序。

### `./run.sh run <类名> [参数...]` — 运行指定的 main

显式指定主类。类名带不带包名都行，主要给"文件里没写 `package`"的情况用：

```sh
./run.sh run HelloWorld              # 文件里没写 package，类名就是 HelloWorld
./run.sh run learn.MyFirst           # 写了 package 也照样能用
./run.sh run learn.MyFirst hello 42  # 后面多出来的原样传给 main 的 args
```

不给类名会报错，不会替你瞎选：

```text
run.sh: run 后面要跟主类名，例如 ./run.sh run HelloWorld
```

### `./run.sh <包名>.<类名> [参数...]` — 上面那条的简写

类名里有包名（文件里写了 `package learn` 之类的声明）时，可以省掉 `run` 两个字：

```sh
./run.sh learn.MyFirst
./run.sh learn.MyFirst hello 42
```

名字从哪来？`./run.sh mains` 里打印的就是。传进去的参数会原样进 main 的 `args`：
上面例子里 `args(0) == "hello"`、`args(1) == "42"`。

一次 JVM 启动里跑多个入口，用下面的 `sbt` 子命令。

### `./run.sh test [测试类...]` — 跑测试

```sh
./run.sh test                        # 跑 src/test/scala 下的全部测试
./run.sh test mylib.BitsSpec         # 只跑一个测试类
./run.sh test mylib.BitsSpec mylib.AluSpec   # 只跑这几个
```

不带测试类时是 sbt 的 `test`；带了就翻译成 `testOnly <测试类...>`。
测试放在 `src/test/scala/`，工程的 `build.sbt` 里已经配好 ScalaTest，写法见下面
「用 Scala 验证子模块的正确性」。

### `./run.sh console` — 带本工程 classpath 的 Scala REPL

```sh
./run.sh console
```

进来的 REPL 能直接 `import` 工程里的代码，适合随手试一段表达式。输入 `:quit` 退出。

```text
scala> println(1 + 1)
2
```

### `./run.sh sbt <命令...>` — 逃生舱

`run.sh` 没包到的用法，直接交给 sbt。**每个带引号的参数是一条独立的 sbt 命令**，
所以要把整条命令（含空格）用引号括起来：

```sh
./run.sh sbt compile                                  # 等价于 ./run.sh compile
./run.sh sbt "testOnly mylib.BitsSpec"                # 一条命令里有空格，必须带引号
./run.sh sbt "runMain learn.A" "runMain learn.B"      # 一次跑两个入口，只付一次启动开销
./run.sh sbt "show discoveredMainClasses"             # 等价于 ./run.sh mains
./run.sh sbt "~runMain learn.MyFirst"                 # 进不了交互模式时，也能用 ~ 守着跑
```

不跟任何命令时会被拒绝：

```text
run.sh: sbt 后面要跟至少一条命令，例如 ./run.sh sbt "runMain learn.A"
```

### `./run.sh help` — 用法速查

打印 `run.sh` 文件头部那份用法说明。忘了子命令有哪些时敲这个，比翻文档快。

## 常见任务

| 我想…… | 敲这个 |
| --- | --- |
| 看有哪些程序能跑 | `./run.sh mains` |
| 只想知道代码编不编得过 | `./run.sh compile` |
| 跑某个 main | `./run.sh <全限定名>` 或 `./run.sh run <类名>` |
| 给 main 传参数 | 直接把参数写在类名后面 |
| 一次跑好几个 main | `./run.sh sbt "runMain A" "runMain B"` |
| 改一行就自动重跑 | `./run.sh`，然后在提示符里 `~runMain <类名>` |
| 跑测试 | `./run.sh test` |
| 随手试一段 Scala | `./run.sh console` |

## 共享 package

- 共享代码放 `src/main/scala/<包目录>/` 里，文件开头写 `package <包名>`；
- 别的 main 直接 `import <包名>.<对象>`，或者写全限定名；
- 同一个工程本来就是同一份 classpath，不存在"要链接"或"要声明依赖"这一步。

目录结构和 `package` 声明不强求一致，但建议保持一致，否则人和 IDE 都容易看糊涂。

## 用 Scala 验证子模块的正确性

比"写个 main 打印一下"更靠谱的是写测试：`build.sbt` 里已经配好 ScalaTest。
用例放在 `src/test/scala/`，例如：

```scala
package mylib

import org.scalatest.funsuite.AnyFunSuite

class BitsSpec extends AnyFunSuite {
  test("加法进位") {
    assert(Bits.add(1, 1) == 2)
  }
}
```

运行：

```sh
./run.sh test
./run.sh test mylib.BitsSpec
```

好处是失败会直接指到哪一条，每加一个用例就多一层保险，改坏了立刻知道。
后面写 CPU 的子模块（译码、ALU、流水线寄存器……）时，可以先用这种"纯 Scala 版本"
把逻辑和边界情况验证清楚，再翻译成 Chisel。

## 常见疑问

**为什么建议一直用 `runMain`，而不是只写 `run`？**
`run` 不带类名时，工程里有多个 main 的话 sbt 会列出编号让你在终端里选一个；
只有一个时就直接跑。实测在非交互环境里（脚本里、`< /dev/null`）这一步会直接失败：

```text
Multiple main classes detected. Select one to run:
 [1] learn.Generate
 [2] tmpone.First
Enter number: [error] java.lang.RuntimeException: No main class detected.
```

所以 `run.sh` 一律帮你翻译成 `runMain`。

**每个 main 会变成独立的可执行文件吗？**
不会。sbt 默认编译出**一份共用的** class 文件，入口在运行时用 `runMain` 选。
如果以后确实需要打成独立 jar（比如交给别人跑），要往 `build.sbt` 里加 assembly
之类的插件并下载新依赖——那属于改框架，先说一声再动。

**不加包名会怎样？**
类会落在"默认包"里，全限定名就是类名本身、没有点号，于是 `./run.sh HelloWorld`
这种写法会被当成拼错的子命令。用 `./run.sh run HelloWorld` 即可，或者干脆在文件里写上
`package learn` 之类的声明（推荐，和工程里其它文件一致，以后搬进 CPU 代码也不用改名）。

## 和硬件流程的关系

`scripts/gen.sh` 本质上也是 `runMain` 的包装，只是它会多传一个输出目录参数、
提示语写的是 gen。纯 Scala 练习用 `run.sh` 更清楚；要生成 Verilog 时才用：

```sh
source scripts/env.sh                                   # 让 WSL 找到课程工具链
scripts/gen.sh learn learn.Generate learn/build/generated
```

## 附录：run.sh 等价的原命令

不想用脚本、或者想看它到底做了什么，可以直接调 sbt 启动器（要先 `cd` 到 `learn`）：

```sh
java -Xmx2G -jar ../tools/sbt-launch.jar                        # 交互模式
java -Xmx2G -jar ../tools/sbt-launch.jar compile
java -Xmx2G -jar ../tools/sbt-launch.jar clean
java -Xmx2G -jar ../tools/sbt-launch.jar "show discoveredMainClasses"
java -Xmx2G -jar ../tools/sbt-launch.jar "runMain learn.MyFirst"
java -Xmx2G -jar ../tools/sbt-launch.jar "runMain learn.MyFirst hello 42"
java -Xmx2G -jar ../tools/sbt-launch.jar "runMain HelloWorld"
java -Xmx2G -jar ../tools/sbt-launch.jar test
java -Xmx2G -jar ../tools/sbt-launch.jar "testOnly mylib.BitsSpec"
java -Xmx2G -jar ../tools/sbt-launch.jar console
```

`run.sh` 做的就是：找到仓库里的启动器、切到工程目录、把上面的命令送进 sbt，
并帮忙挡住几种容易写错的用法。
