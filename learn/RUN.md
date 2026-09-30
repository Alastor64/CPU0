# 在 learn 里编译与运行 Scala 程序

`learn` 是一个 sbt 工程，启动器已经在仓库里（`tools/sbt-launch.jar`），**不需要额外安装**。
sbt 会把 `src/main/scala/` 下的**所有**文件一起编译成一份 classpath，其中每个带 `main`
的 `object` 都是一个独立入口，运行时点名要跑哪一个。

所以加新的练习不需要新建工程、也不用改 `build.sbt`：直接往 `src/main/scala/` 里放文件就行。
加多少个 main 都互不影响（只有在同一个包里出现同名定义才会冲突）。

常用的命令都包在同目录的 `run.sh` 里。下面讲怎么用它，附录里给出它等价的原命令。

## 快速上手

在 WSL 里执行（脚本放在任何目录下都能调用，它会自己切到 learn 工程）：

```sh
cd /mnt/d/CPU/my/learn

./run.sh                           # 进 sbt 交互模式
./run.sh compile                   # 只编译
./run.sh test                      # 跑 src/test/scala 下的全部测试
./run.sh test mylib.BitsSpec       # 只跑指定的测试类（可以写多个）
./run.sh learn.MyFirst             # 编译并运行这个 main
./run.sh learn.MyFirst hello 42    # 运行 main，并把 hello、42 传给它
./run.sh clean                     # 删掉编译产物
```

约定很简单：**带点号的参数当成主类的全限定名**（包名.对象名），其余当成 sbt 子命令。
子命令有 `compile` / `clean` / `console` / `test` / `sbt`：

- `./run.sh console` 打开带本工程 classpath 的 Scala REPL；
- `./run.sh sbt <命令...>` 是逃生舱，后面的参数原样交给 sbt，例如一次跑多个入口。

## 编译

```sh
./run.sh compile
```

产物在 `target/scala-2.13/classes`（已被 `.gitignore` 忽略，不会提交）。

## 运行指定的 main

```sh
./run.sh learn.MyFirst
```

多给的参数会原样进 `args`（下面例子里 `args(0) == "hello"`、`args(1) == "42"`）：

```sh
./run.sh learn.MyFirst hello 42
```

想在一次 JVM 启动里跑多个入口，用 `sbt` 子命令一次给多条命令：

```sh
./run.sh sbt "runMain learn.MyFirst" "runMain learn.verify.Second"
```

## 交互模式：改一行就跑

```sh
./run.sh
```

进入 sbt 提示符之后：

```text
> ~runMain learn.MyFirst
```

`~` 前缀表示"文件一改就重新编译并重跑"，学语法时最省事；`~test` 同理。

交互模式需要真的终端。标准输入被重定向时脚本会直接报错退出，免得 sbt 在那儿空转刷提示符。

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

## 两个常见疑问

**为什么建议一直用 `runMain`，而不是只写 `run`？**
`run` 不带类名时，工程里有多个 main 的话 sbt 会列出编号让你在终端里选一个；
只有一个时就直接跑。实测在非交互环境里（脚本里、`< /dev/null`）这一步会直接失败：

```text
Multiple main classes detected. Select one to run:
 [1] learn.Generate
 [2] tmpprobe.MainA
Enter number: [error] java.lang.RuntimeException: No main class detected.
```

所以 `run.sh` 一律帮你翻译成 `runMain`。

**每个 main 会变成独立的可执行文件吗？**
不会。sbt 默认编译出**一份共用的** class 文件，入口在运行时用 `runMain` 选。
如果以后确实需要打成独立 jar（比如交给别人跑），要往 `build.sbt` 里加 assembly
之类的插件并下载新依赖——那属于改框架，先说一声再动。

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
java -Xmx2G -jar ../tools/sbt-launch.jar compile
java -Xmx2G -jar ../tools/sbt-launch.jar "runMain learn.MyFirst"
java -Xmx2G -jar ../tools/sbt-launch.jar "runMain learn.MyFirst hello 42"
java -Xmx2G -jar ../tools/sbt-launch.jar test
java -Xmx2G -jar ../tools/sbt-launch.jar "testOnly mylib.BitsSpec"
```

`run.sh` 做的就是：找到仓库里的启动器、切到工程目录、把上面的命令送进 sbt。
