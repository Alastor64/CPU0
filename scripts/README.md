# 共享脚本

这三个脚本是 `v0`（CPU 本体）和 `learn`（学 Chisel）共用的流程。
它们跑在 WSL 里，用的是课程工具链 AppImage 的解包产物（`tools/cpu2026`）。

| 脚本 | 作用 | 一句话 |
| --- | --- | --- |
| `setup-tools.sh` | 解包课程工具链 | 只需要跑一次 |
| `env.sh` | 设置环境变量 | 用 `source` 加载，也可给助教框架的 `make` 用 |
| `sbt.sh` | Linux 原生 sbt | 直接跑 `tools/sbt-launch.jar`，命令行与编辑器共用 |
| `gen.sh` | Chisel → SystemVerilog | 调 sbt 运行工程里的导出主类 |
| `sim.sh` | Verilator 仿真 | 编译「设计 + 测试台」并运行 |
| `synth.sh` | 面积 + 频率 | Yosys 映射到 ASAP7 标准单元，OpenSTA 算时序 |

## 编辑器（Metals）

WSL 的 PATH 里会混进 Windows 侧的工具。如果 Metals 找到了 Windows 版 `sbt`
（例如 scoop 装的那个），它会用 Windows 的 JDK 解析依赖，生成一片 `C:\...`
路径的 bloop 配置；WSL 侧的 bloop 读不了这些路径，表现为工程一直
`no build target found`（补全、跳转、报错全部失效）。

所以在 WSL 远程设置里把 sbt 指到本仓库的 `scripts/sbt.sh`：

```json
"metals.sbtScript": "<仓库绝对路径>/scripts/sbt.sh",
"metals.autoImportBuilds": "initial"
```

改完重载窗口，Metals 就会用它导入构建。

保存时的 Scala 格式化由 `.scalafmt.conf` 决定：每个 sbt 工程根目录放一份
（Metals 只在工作区根目录找），没有它每次保存都会弹「找不到 .scalafmt.conf」。

## 约定

- **换行符**：仓库根的 `.gitattributes` 强制这些脚本与源码用 LF，
  否则在 WSL 里会报 `bad interpreter`。
- **不重复造轮子**：综合和时序分析的库、约束都沿用助教框架的定义
  （ASAP7 RVT TT 五个库 + 框架的 `timing.tcl`），这样本地看到的面积、
  频率含义与官方评测一致。
- **构建产物**：统一放在各自工程的 `build/` 下（已被 git 忽略）。

## 完整示例

```sh
source scripts/env.sh
scripts/gen.sh learn learn.Generate learn/build/generated
scripts/sim.sh tb_counter learn/build/sim_counter \
    learn/build/generated/counter_w8.sv learn/tb/tb_counter.sv
scripts/synth.sh counter_w8 learn/build/synth_counter \
    learn/build/generated/counter_w8.sv
```

第三个脚本会打印这样的结果：

```text
  总面积:       12.345 um^2
  标准单元:        23 个
  最高频率:    1234.56 MHz
```
