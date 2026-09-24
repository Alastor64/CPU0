# 共享脚本

这三个脚本是 `v0`（CPU 本体）和 `learn`（学 Chisel）共用的流程。
它们跑在 WSL 里，用的是课程工具链 AppImage 的解包产物（`tools/cpu2026`）。

| 脚本 | 作用 | 一句话 |
| --- | --- | --- |
| `setup-tools.sh` | 解包课程工具链 | 只需要跑一次 |
| `env.sh` | 设置环境变量 | 用 `source` 加载，也可给助教框架的 `make` 用 |
| `gen.sh` | Chisel → SystemVerilog | 调 sbt 运行工程里的导出主类 |
| `sim.sh` | Verilator 仿真 | 编译「设计 + 测试台」并运行 |
| `synth.sh` | 面积 + 频率 | Yosys 映射到 ASAP7 标准单元，OpenSTA 算时序 |

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
