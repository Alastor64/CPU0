# CPU 项目工作区

本仓库是 RISC-V RV32IM CPU 项目的开发仓库（`v0` 为第一版设计）。
助教下发的课程框架放在同级目录 `RISC-V-CPU-2026/`，那是一个独立的 git
仓库，**只读、只 pull，不能 push**。

## 目录结构

```text
my/
├── v0/        # CPU 本体：Chisel 源码（当前只有冒烟例子）
├── learn/     # 学 Chisel 用的独立工程，与 CPU 无关，但走同一套流程
├── scripts/   # 三个共享脚本：生成 Verilog / 仿真 / 综合
├── tools/     # 本地工具链（不提交）：sbt 启动器 + 解包后的课程 AppImage
├── design/    # 架构设计说明（只读，由项目负责人维护）
└── asap7/     # ASAP7 工艺库的本地克隆（不提交）
```

`v0` 和 `learn` 是两个相互独立的 sbt 工程：它们各自有 `build.sbt`，互不影响，
但共用 `scripts/` 里的同一套「生成 → 仿真 → 综合」流程，避免重复造轮子。

## 一次性准备

在 WSL 里执行（本项目是 Linux 项目，所有硬件工具都跑在 WSL 里）：

```sh
scripts/setup-tools.sh          # 把课程工具链 AppImage 解包到 tools/cpu2026
```

## 日常流程

```sh
source scripts/env.sh                                  # 把课程工具链放进 PATH

# 1) Chisel -> SystemVerilog
scripts/gen.sh learn learn.Generate learn/build/generated

# 2) 仿真（Verilator 编译「设计 + 测试台」并运行）
scripts/sim.sh tb_counter learn/build/sim_counter \
    learn/build/generated/counter_w8.sv learn/tb/tb_counter.sv

# 3) 综合（Yosys 出面积，OpenSTA 出最高频率）
scripts/synth.sh counter_w8 learn/build/synth_counter \
    learn/build/generated/counter_w8.sv
```

CPU 本体的完整评测走助教框架的 `make`（正确性、IPC、面积、时序），
细节见 `RISC-V-CPU-2026/README-ZH.md`；本仓库的 `scripts/` 只负责
把 Chisel 源码变成可以喂给框架、也可以单独验证的 Verilog。

## Chisel 语言版本

`v0` 与 `learn` 使用同一套版本：Scala 2.13.18 + Chisel 7.15.0，
构建工具是 sbt 1.12.4（用 `tools/sbt-launch.jar` 启动，不需要额外安装）。
