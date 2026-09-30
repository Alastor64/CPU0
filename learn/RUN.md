# 在 learn 里编译与运行 Scala 程序

这个工程用 sbt 构建，启动器已经在仓库里（`tools/sbt-launch.jar`），**不需要额外安装**。
sbt 会把 `src/main/scala/` 下的**所有**文件一起编译成一份 classpath，其中每个带
`main` 的 `object` 都是一个独立入口，运行时用 `runMain` 点名要跑哪一个。

所以：想加新的练习，直接往 `src/main/scala/` 里放文件就行，不用新建工程、
也不用改 `build.sbt`。加多少个 main 都互不影响（同一个包里出现同名定义才会冲突）。

下面的命令都在 WSL 里执行，先切到工程目录：

```sh
cd /mnt/d/CPU/my/learn
```

## 编译

```sh
java -Xmx2G -jar ../tools/sbt-launch.jar compile
```

产物在 `target/scala-2.13/classes`（已被 `.gitignore` 忽略，不会提交）。

## 运行指定的 main

全限定名 = 包名 + 对象名，用它指定入口：

```sh
java -Xmx2G -jar ../tools/sbt-launch.jar "runMain learn.MyFirst"
```

一次启动跑多个入口、并且给 main 传参数：

```sh
java -Xmx2G -jar ../tools/sbt-launch.jar \
  "runMain learn.MyFirst hello 42" \
  "runMain learn.verify.Second"
```

参数会原样进 `args`（上面例子里 `args(0) == "hello"`、`args(1) == "42"`）。
一次启动多个命令的好处是只付一次 JVM 和加载工程的启动开销。

## 只写 `run` 会怎样

`run` 不带类名时，如果工程里有多个 main，sbt 会列出来让你在终端里输编号选一个；
只有一个时就直接跑。实测的非交互环境下（例如脚本里、`< /dev/null`）这一步会直接失败：

```text
Multiple main classes detected. Select one to run:
 [1] learn.Generate
 [2] tmpprobe.MainA
Enter number: [error] java.lang.RuntimeException: No main class detected.
```

所以不管是自己敲还是写在脚本里，都建议固定用 `runMain`。

## 改一行就跑：交互模式

```sh
java -Xmx2G -jar ../tools/sbt-launch.jar
```

进入 sbt 之后：

```text
> ~runMain learn.MyFirst
```

`~` 前缀表示"文件一改就重新编译并重跑"，学语法时最省事；`~test` 同理。

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
java -Xmx2G -jar ../tools/sbt-launch.jar test
java -Xmx2G -jar ../tools/sbt-launch.jar "testOnly mylib.BitsSpec"
```

好处是失败会直接指到哪一条，每加一个用例就多一层保险，改坏了立刻知道。
后面写 CPU 的子模块（译码、ALU、流水线寄存器……）时，可以先用这种"纯 Scala 版本"
把逻辑和边界情况验证清楚，再翻译成 Chisel。

## 两个常见疑问

**每个 main 会变成独立的可执行文件吗？**
不会。sbt 默认编译出**一份共用的** class 文件，入口在运行时用 `runMain` 选。
如果以后确实需要打成独立 jar（比如交给别人跑），要往 `build.sbt` 里加 assembly
之类的插件并下载新依赖——那属于改框架，先说一声再动。

**命令太长记不住怎么办？**
可以在 WSL 的 `~/.bashrc` 里加个别名：

```sh
alias sbtl='java -Xmx2G -jar /mnt/d/CPU/my/tools/sbt-launch.jar'
```

之后 `sbtl "runMain learn.MyFirst"` 就够用了。

## 和硬件流程的关系

`scripts/gen.sh` 本质上也是 `runMain` 的包装，只是它会多传一个输出目录参数、
提示语写的是 gen。纯 Scala 练习用本文的命令更清楚；要生成 Verilog 时才用：

```sh
source scripts/env.sh                                   # 让 WSL 找到课程工具链
scripts/gen.sh learn learn.Generate learn/build/generated
```
