# 共享脚本

本目录的脚本是 `v0`（CPU 本体）和 `learn`（学 Chisel）共用的构建流程。
它们运行在 WSL 中，默认使用仓库自带的课程工具链：

- `tools/cpu2026/`：解包后的 Verilator、Yosys、OpenSTA 与 ASAP7 库
- `tools/sbt-launch.jar`：启动 Scala/sbt 构建

除特别说明外，下面的命令都从本仓库根目录（`my/`）执行。输入路径按当前
目录解析；`gen.sh` 的输出路径改按仓库根目录解析，见对应章节。

## 脚本总览

| 脚本 | 作用 |
| --- | --- |
| `setup-tools.sh` | 把课程工具链 AppImage 解包到 `tools/cpu2026/` |
| `env.sh` | 设置 sbt 与硬件工具链环境变量，必须用 `source` 加载 |
| `sbt.sh` | 在 WSL 中启动 Linux 版 sbt，并把参数原样转发 |
| `gen.sh` | 运行 Chisel 导出主类，生成 SystemVerilog |
| `sim.sh` | 用 Verilator 编译设计、测试台并运行仿真 |
| `test-correctness.sh` | 生成或复用 CPU 仿真器并运行 correctness 用例 |
| `synth.sh` | 用 Yosys 估算面积，用 OpenSTA 估算时序 |
| `publish.sh` | 把生成的 CPU RTL 同步到 `CPU-submit`，可选提交和推送 |

## 一次性准备

### `setup-tools.sh`

```sh
scripts/setup-tools.sh                 # 自动查找 AppImage
scripts/setup-tools.sh <AppImage 路径>  # 显式指定 AppImage
```

不传路径时，脚本依次查找：

1. `tools/cpu2026-tools-x86_64.AppImage`
2. 仓库上一级目录中的 `cpu2026-tools-x86_64.AppImage`
3. `RISC-V-CPU-2026/` 中的 `cpu2026-tools-x86_64.AppImage`

脚本把 AppImage 解包到 `tools/cpu2026/`，并检查 Verilator、Yosys、OpenSTA
以及 ASAP7 的五个 `.lib`。工具链已经有效时会直接跳过，不会重复解包。
`tools/` 已被 Git 忽略，不进入提交。

### `env.sh`

```sh
source scripts/env.sh
```

该脚本设置：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `CPU2026_APPDIR` | `tools/cpu2026` | 课程工具链根目录 |
| `CPU2026_ASAP7_LIB` | `tools/cpu2026/asap7/lib` | 综合使用的五个 ASAP7 `.lib` |
| `CPU2026_MAKE` | `../tools/make-4.4.1-install/bin/make` | GNU Make 4.4.x 可执行文件 |
| `SBT_LAUNCH` | `tools/sbt-launch.jar` | sbt 启动器 |

它还会把 `tools/cpu2026/bin` 和 Make 4.4 所在目录加入 `PATH`，并导出
`MAKE` 供 Verilator 内部构建使用。本目录中的 `sbt.sh`、`gen.sh`、
`sim.sh`、`synth.sh` 和 `publish.sh` 会自动加载该环境，因此单独调用包装脚本
时不必先 `source`。直接调用 `verilator`、`yosys`、`sta`，或让助教框架执行
`make` 时，仍需先加载：

```sh
source scripts/env.sh
make -C ../RISC-V-CPU-2026 ...
```

`env.sh` 会校验 Make 必须是 4.4.x；未安装时给出 `CPU2026_MAKE` 错误，
避免静默退回系统自带的 Make 4.3。

## 构建与生成

### `sbt.sh`

```sh
cd v0
../scripts/sbt.sh compile
../scripts/sbt.sh test
```

`sbt.sh` 在**当前目录**运行 sbt，所以要先进入 `v0/` 或 `learn/`。
所有参数都会原样转发给 sbt。脚本使用仓库中的 `tools/sbt-launch.jar`，
堆上限为 2 GB。

它也可以作为 Metals 的 `metals.sbtScript`，见文末的编辑器配置。

### `gen.sh`

```sh
scripts/gen.sh <工程目录> <导出主类> [输出目录]
```

示例：

```sh
# 导出 CPU 顶层 student_top.sv
scripts/gen.sh v0 cpu.generate.Generate v0/build/generated

# 导出 learn 中的所有示例模块
scripts/gen.sh learn learn.Generate learn/build/generated
```

输出目录相对于仓库根目录；也可以传绝对路径。`v0` 当前导出一个
`student_top.sv`，`learn` 会为每个登记的设计分别生成文件。

首次运行会下载 Scala、Chisel 及编译器插件依赖。之后默认使用本地缓存。
生成文件位于各工程的 `build/` 下，已被 Git 忽略。

## 仿真

### `sim.sh`

```sh
scripts/sim.sh <顶层模块名> <输出目录> <文件...>
```

示例：

```sh
scripts/sim.sh tb_counter learn/build/sim_counter \
    learn/build/generated/counter_w8.sv \
    learn/tb/tb_counter.sv
```

顶层模块应当是测试台，测试台需自行提供时钟、激励和结束条件。
Verilator 启用 `--timing` 与 `-Wall -Wno-fatal`，因此会报告警告但不会因警告
中止。脚本每次都会清理 `<输出目录>/obj` 和 `<输出目录>/sim` 后重新编译，
避免 Windows 盘的时间戳精度导致运行旧仿真器。

运行入口是 `<输出目录>/sim`。

## 正确性回归

### `test-correctness.sh`

```sh
scripts/test-correctness.sh
scripts/test-correctness.sh --case correctness_hanoi
scripts/test-correctness.sh --directed path/to/testcases
scripts/test-correctness.sh --rebuild
```

脚本默认运行课程框架中的全部 `correctness_*` 用例，但跳过耗时较长的
`correctness_qsort` 和 `correctness_pi`。需要运行这两个用例时，要么显式指定
`--case`，要么在完整回归中增加 `--include-slow`。

参数：

| 参数 | 作用 |
| --- | --- |
| `--case <名称>` | 只运行指定用例；不受默认慢测试过滤影响 |
| `--directed <目录>` | 额外运行指定目录中的定向用例 |
| `--include-slow` | 完整回归时包含 qsort 和 pi |
| `--rebuild` | 强制重新生成 RTL 并编译仿真器 |
| `--max-cycles <数量>` | 覆盖单个用例的周期上限 |
| `--latency <数值>` | 覆盖测试台的响应延迟参数 |
| `-h`, `--help` | 打印用法 |

可调环境变量：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `RISC_V_CPU_2026_DIR` | `../RISC-V-CPU-2026` | 课程框架目录 |
| `SIM` | `v0/build/correctness-sim/sim` | 已构建仿真器路径 |
| `MAX_CYCLES` | `12000000` | 用例周期上限 |
| `LATENCY` | `10` | 测试台响应延迟参数 |
| `JOBS` | `4` | 重新编译仿真器时的并行任务数 |
| `CPU2026_MAKE` | `../tools/make-4.4.1-install/bin/make` | 重新编译时使用的 GNU Make 4.4.x |

当 `--rebuild` 生效或默认仿真器不存在时，脚本会：

1. 生成 RTL 到 `v0/build/generated-correctness/`
2. 写入只包含 `student_top.sv` 的临时 filelist
3. 调用课程框架的 `make build`
4. 把仿真器编译到 `v0/build/correctness-sim/sim`

按此流程重新构建时，需要先运行 `source scripts/env.sh`，或者在课程框架中
配置可用的 Verilator。脚本只复用已有 `SIM` 时不需要重新构建。

## 综合与时序

### `synth.sh`

```sh
scripts/synth.sh <顶层模块名> <输出目录> <文件...>
```

示例：

```sh
scripts/synth.sh counter_w8 learn/build/synth_counter \
    learn/build/generated/counter_w8.sv
```

注意：脚本开始前会清空输出目录中已有的内容，输出目录应使用专门的构建目录。

可调环境变量：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `CLOCK_PERIOD_NS` | `2.0` | 综合目标周期，单位 ns |
| `CLOCK_PORT` | `clock` | 时序分析使用的时钟端口名 |
| `CPU2026_FRAMEWORK` | `../RISC-V-CPU-2026` | 提供 `scripts/timing.tcl` 的课程框架 |

主要输出：

| 文件 | 内容 |
| --- | --- |
| `stat.json` | 总面积、标准单元数 |
| `mapped.v` | 映射到 ASAP7 标准单元后的网表 |
| `design.json` | Yosys 设计数据库 |
| `synth.log` | Yosys 完整日志 |
| `timing.rpt` | OpenSTA 时序报告 |
| `timing.log` | OpenSTA 完整日志 |
| `timing_values.json` | 最高频率、最小周期和建立裕量 |

如果 `CPU2026_FRAMEWORK/scripts/timing.tcl` 不存在，综合和面积报告仍会完成，
但时序分析会跳过。该流程不处理 fakeram，顶层模块名也不受课程框架限定。

## 发布到 CPU-submit

`publish.sh` 使用固定的 `v0` 导出主类，执行以下流程：

1. 调用 `gen.sh` 生成 `v0/build/generated/student_top.sv`
2. 复制到 `CPU-submit/verilog/generated/student_top.sv`
3. 如果 `verilog/filelist.f` 缺少条目，追加 `generated/student_top.sv`
4. 根据当前 `my` 仓库提交生成可追溯的提交信息

用法：

```sh
scripts/publish.sh               # 只更新 CPU-submit，不提交、不推送
scripts/publish.sh --dry-run     # 报告要更新什么，不改 CPU-submit
scripts/publish.sh --commit      # 更新并在 CPU-submit 本地提交
scripts/publish.sh --push        # 更新、提交并推送到个人远端 origin
```

`--push` 隐含 `--commit`。提交时只暂存以下两个文件，避免带入提交仓库中的
其他改动：

- `verilog/generated/student_top.sv`
- `verilog/filelist.f`

推送只使用 `CPU-submit` 的 `origin`，不会使用课程框架的只读 `upstream`。
`--dry-run` 不会修改 `CPU-submit`，但仍会执行 Chisel 生成，因此
`v0/build/generated/` 会更新。

可通过环境变量指定提交仓库位置：

```sh
CPU_SUBMIT_DIR=/path/to/CPU-submit scripts/publish.sh --push
```

默认位置是仓库同级的 `../CPU-submit`。目标目录必须是带
`verilog/filelist.f` 的 Git 仓库。

## 编辑器（Metals）

WSL 的 `PATH` 里可能混入 Windows 侧的 `sbt`。如果 Metals 使用 Windows sbt，
会用 Windows JDK 解析依赖并生成 `C:\...` 路径的 bloop 配置；WSL 侧的 bloop
无法读取这些路径，表现为工程一直显示 `no build target found`，补全、跳转和
报错全部失效。

在 WSL 远程设置中把 sbt 指向本仓库的启动脚本：

```json
"metals.sbtScript": "<仓库绝对路径>/scripts/sbt.sh",
"metals.autoImportBuilds": "initial"
```

修改后重载窗口。Scala 格式化由各 sbt 工程根目录的 `.scalafmt.conf` 决定；
Metals 只在工程根目录查找该文件。

## 完整流程

```sh
cd /mnt/d/CPU/my

# 首次准备
scripts/setup-tools.sh
source scripts/env.sh

# 生成、仿真、综合
scripts/gen.sh learn learn.Generate learn/build/generated
scripts/sim.sh tb_counter learn/build/sim_counter \
    learn/build/generated/counter_w8.sv learn/tb/tb_counter.sv
scripts/test-correctness.sh --case correctness_add_to_100
scripts/synth.sh counter_w8 learn/build/synth_counter \
    learn/build/generated/counter_w8.sv

# 更新 CPU-submit；确认差异后再推送
scripts/publish.sh --dry-run
scripts/publish.sh --push
```

综合成功时会打印类似结果：

```text
  总面积:       12.345 um^2
  标准单元:        23 个
  最高频率:    1234.56 MHz
```

## 约定与排查

- 所有脚本与源码使用 LF 换行，仓库根 `.gitattributes` 已强制这一点。
- 构建产物统一放在各工程的 `build/` 下，并已被 Git 忽略。
- 遇到 `找不到工具链目录`：先运行 `scripts/setup-tools.sh`。
- 遇到 `找不到 sbt 启动器`：确认 `tools/sbt-launch.jar` 存在；下载方式见
  `tools/sbt.cmd` 中的注释。
- 遇到综合没有时序结果：检查 `../RISC-V-CPU-2026/scripts/timing.tcl`，
  或设置 `CPU2026_FRAMEWORK`。
