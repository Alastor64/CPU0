package cpu

import chisel3._
import chisel3.util.log2Ceil

/** 课程框架提供的同步单端口 SRAM。
  *
  * 该模块只声明 `sram_fakeram` 的外部接口，不重复实现存储体；仿真模型和
  * 综合模型均由 RISC-V-CPU-2026 框架自动提供。对应文档见
  * `RISC-V-CPU-2026/docs/sram.md`。
  *
  * 参数含义：
  *   - `depth`：可寻址的存储字数量；
  *   - `width`：每个存储字的位宽；
  *   - `writeGranularity`：每个写掩码位控制的连续数据位宽。例如
  *     `width = 32`、`writeGranularity = 8` 时，`wmask` 为 4 位，可用于
  *     实现字节写使能。
  *
  * 使用时必须满足以下时序约定：
  *   - `en = 1` 时的一次访问占用该 SRAM 唯一端口，读和写不能在同一周期发生；
  *   - `en = 1, we = 0` 发起读，`rdata` 在下一个时钟周期有效；
  *   - 写周期和 `en = 0` 的周期中，`rdata` 没有有效语义；
  *   - `addr` 是字索引而非字节地址，且访问时必须小于 `depth`。
  *
  * @param depth 存储字数量，合法范围 1..1048576
  * @param width 存储字位宽，合法范围 1..4096
  * @param writeGranularity 写掩码粒度，必须是 `width` 的正因子
  */
class SramFakeram(
  depth: Int,
  width: Int,
  writeGranularity: Int,
) extends ExtModule(
      Map(
        "DEPTH" -> IntParam(depth),
        "WIDTH" -> IntParam(width),
        "WRITE_GRANULARITY" -> IntParam(writeGranularity),
      )
    ) {

  // 在 Scala 侧提前检查参数，避免等到框架仿真或综合阶段才报出模糊错误。
  // 单个实例的容量限制来自课程文档：DEPTH * WIDTH <= 2^24 位。
  require(depth >= 1 && depth <= 1048576, s"SRAM depth 必须在 1..1048576，当前为 $depth")
  require(width >= 1 && width <= 4096, s"SRAM width 必须在 1..4096，当前为 $width")
  require(
    depth.toLong * width.toLong <= (1L << 24),
    s"SRAM 容量不能超过 16777216 位，当前为 ${depth.toLong * width.toLong} 位",
  )
  require(
    writeGranularity >= 1 && writeGranularity <= width,
    s"SRAM writeGranularity 必须在 1..$width，当前为 $writeGranularity",
  )
  require(
    width % writeGranularity == 0,
    s"SRAM writeGranularity 必须整除 width，当前 width=$width，writeGranularity=$writeGranularity",
  )

  override def desiredName = "sram_fakeram"

  // 与框架中 sram_fakeram 的端口名、方向和位宽逐项一致。
  // 即使 DEPTH=1 也保留至少 1 位地址，和框架接口定义保持一致。
  val clk   = IO(Input(Clock()))
  val en    = IO(Input(Bool()))
  val we    = IO(Input(Bool()))
  val wmask = IO(Input(UInt((width / writeGranularity).W)))
  val addr  = IO(Input(UInt(math.max(1, log2Ceil(depth)).W)))
  val wdata = IO(Input(UInt(width.W)))
  val rdata = IO(Output(UInt(width.W)))
}
