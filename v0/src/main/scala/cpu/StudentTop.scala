package cpu

import chisel3._

/** 课程要求的顶层模块。
  *
  * 硬性约束（见 RISC-V-CPU-2026/README-ZH.md）：
  *   - 模块名必须是 `student_top`；
  *   - 端口必须严格是下面这些 AXI4-Lite 信号，不能多也不能少；
  *   - 复位是高电平有效，框架启动时保持 5 个周期；
  *   - 访存地址必须 4 字节对齐，读出的是 32 位数据。
  *
  * 当前状态：**空转骨架**。所有 valid 拉低、所有 ready 拉低，
  * 既不发起事务也不接收响应。它存在的意义是把
  * 「Chisel 生成 SystemVerilog -> 框架编译 -> 测试脚本运行」这条链路先证明通，
  * 真正的流水线会在 my/design 的架构确定之后长在这个骨架上。
  *
  * 方向约定（M->S 表示 CPU 输出给内存，M<-S 表示内存输出给 CPU）：
  *   AR：araddr/arvalid 输出，arready 输入
  *   R ：rdata/rresp/rvalid 输入，rready 输出
  *   AW：awaddr/awvalid 输出，awready 输入
  *   W ：wdata/wstrb/wvalid 输出，wready 输入
  *   B ：bresp/bvalid 输入，bready 输出
  *
  * 注意两处写法上的刻意选择：
  *   1. 不用 Bundle，而是逐个声明 IO，这样生成的端口名就是 araddr/arvalid/...，
  *      不会带上 io_ 前缀（框架的仿真器按这些名字连端口）。
  *   2. clock 和 reset 是 Chisel 每个模块自带的隐式端口，不用（也不能）声明；
  *      RequireSyncReset 表示使用同步复位，与框架的期望一致。
  */
class StudentTop extends Module with RequireSyncReset {
  override def desiredName = "student_top"

  // ---- 读地址通道 (AR) ----
  val araddr  = IO(Output(UInt(32.W)))
  val arvalid = IO(Output(Bool()))
  val arready = IO(Input(Bool()))

  // ---- 读数据通道 (R) ----
  val rdata  = IO(Input(UInt(32.W)))
  val rresp  = IO(Input(UInt(2.W)))
  val rvalid = IO(Input(Bool()))
  val rready = IO(Output(Bool()))

  // ---- 写地址通道 (AW) ----
  val awaddr  = IO(Output(UInt(32.W)))
  val awvalid = IO(Output(Bool()))
  val awready = IO(Input(Bool()))

  // ---- 写数据通道 (W) ----
  val wdata  = IO(Output(UInt(32.W)))
  val wstrb  = IO(Output(UInt(4.W)))
  val wvalid = IO(Output(Bool()))
  val wready = IO(Input(Bool()))

  // ---- 写响应通道 (B) ----
  val bresp  = IO(Input(UInt(2.W)))
  val bvalid = IO(Input(Bool()))
  val bready = IO(Output(Bool()))

  // 空转：什么都不做。真正的实现会把这些信号接到访存部件上。
  araddr  := 0.U
  arvalid := false.B
  rready  := false.B

  awaddr  := 0.U
  awvalid := false.B
  wdata   := 0.U
  wstrb   := 0.U
  wvalid  := false.B
  bready  := false.B
}
