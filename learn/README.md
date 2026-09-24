# learn：学 Chisel 的练习场

这个工程里的东西和 CPU 无关，只是用来把 Chisel 的常用写法过一遍。
它和 `v0` 是同级、互相独立的两个 sbt 工程，但**共用同一套工具链和流程**：

```text
Chisel 源码 --(sbt)--> SystemVerilog --(Verilator)--> 仿真波形/打印
                                     --(Yosys+ASAP7+OpenSTA)--> 面积/频率
```

也就是说：每学一个写法，都能顺手看到它综合出来多少面积、能跑多快——
这和 CPU 项目最后要交的参数敏感度分析是同一套方法论。

## 怎么跑

在 WSL 里，从仓库根目录（`my/`）执行：

```sh
source scripts/env.sh

# 1) 生成 Verilog：把所有示例模块导出到 learn/build/generated/
scripts/gen.sh learn learn.Generate learn/build/generated

# 2) 仿真：跑计数器那个例子（测试台在 tb/tb_counter.sv）
scripts/sim.sh tb_counter learn/build/sim_counter \
    learn/build/generated/counter_w8.sv learn/tb/tb_counter.sv

# 3) 综合：看面积和最高频率
scripts/synth.sh counter_w8 learn/build/synth_counter \
    learn/build/generated/counter_w8.sv
```

第一次执行会下载 Chisel/Scala 依赖（几百 MB，之后走缓存），会慢一些。

## 现有例子

| 模块 | 生成的文件 | 知识点 |
| --- | --- | --- |
| `Counter(width)` | `counter_w8.sv` | `RegInit`、`when`、IO Bundle、`desiredName` |
| `Alu(width)` | `alu_w8.sv` | `MuxLookup`、位运算、移位、位宽语义 |
| `VecSum(n, width)` | `vecsum_n4_w8.sv` | `Vec`、`reduce`、加法树、位宽增长 |

## 学习建议的顺序

1. **基础积木**：`UInt`/`SInt`/`Bool`、`+` 与 `+&` 的位宽差别、`Cat`、切片。
2. **组合逻辑**：`Mux`/`MuxLookup`/`MuxCase`，以及什么时候会用 `switch`/`is`。
3. **时序逻辑**：`Reg`/`RegInit`/`RegEnable`，同步复位与异步复位的区别。
4. **结构化**：`Bundle`、`Vec`、`Wire`/`Reg` 的位置约定（Chisel 的最后连接语义）。
5. **参数化**：类参数、`require` 断言、`desiredName` 命名。
6. **接口**：`Decoupled`（Ready/Valid）——CPU 里的流水线寄存器就靠它。
7. **存储器**：`SyncReadMem`，以及课程要求的外部 SRAM（`sram_fakeram`）。

每一步都可以照上面的命令生成、仿真、综合，看看写法对面积和频率的影响。

## 添加新例子

1. 在 `src/main/scala/learn/` 下写模块（记得写清楚注释）。
2. 在 `Generate.scala` 的 `designs` 列表里登记一行。
3. 需要仿真就加一个 `tb/tb_xxx.sv` 测试台。
4. 重新执行 `scripts/gen.sh`。

## 关于测试台的写法

`tb/tb_counter.sv` 里的激励只在**下降沿**改变输入，检查在上升沿之后进行。
这样写是为了避免仿真器之间的事件顺序差异（同一个时刻既改输入又采样的
竞争写法，会让 Verilator 和其他仿真器给出不同结果）。
