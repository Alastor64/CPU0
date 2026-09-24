// learn 工程的第一组例子。
//
// 这里的每个类都能走同一套流程：
//   scripts/gen.sh   -> SystemVerilog
//   scripts/sim.sh   -> Verilator 仿真
//   scripts/synth.sh -> 面积与最高频率
//
// 注释尽量写细，因为这是学习用的代码，也是两人协作时互相看懂的说明书。
package learn

import chisel3._
import chisel3.util.{MuxLookup, log2Ceil}

/** 例子 1：参数化计数器。
  *
  * 知识点：
  *   - IO Bundle：Chisel 里一个 Bundle 会变成一组 `io_*` 端口；
  *   - RegInit：带复位值的寄存器（这里等价于"reset 时清零"）；
  *   - when：条件赋值，硬件里就是"加一个使能条件"；
  *   - desiredName：手工指定生成的模块名，默认是 Scala 类名。
  *
  * 综合结果参考：width 个触发器 + 一个加法器 + 一个与门。
  */
class Counter(width: Int) extends Module {
  require(width >= 1, "位宽至少 1 bit")

  override def desiredName = s"counter_w$width"

  val io = IO(new Bundle {
    val en = Input(Bool())
    val q  = Output(UInt(width.W))
  })

  // RegInit 的值就是复位后的值；io.en 为假时寄存器保持
  val count = RegInit(0.U(width.W))
  when(io.en) {
    count := count + 1.U
  }
  io.q := count
}

/** 例子 2：组合逻辑 ALU。
  *
  * 知识点：
  *   - MuxLookup：用一张"查表"描述多路选择，比堆 Mux 清楚得多；
  *   - 位宽语义：`a + b` 的结果宽度是两者中较宽的那个，
  *     `a +& b` 会多保留一位进位。要扩展/截断时显式写出来；
  *   - 切片：`b(2, 0)` 取低 3 位，移位的位数必须是 UInt。
  */
class Alu(width: Int = 8) extends Module {
  // 这里只做 2 的幂宽度，是为了让移位位数好算，方便讲解
  require(width >= 2 && (width & (width - 1)) == 0, "宽度取 2 的幂")

  override def desiredName = s"alu_w$width"

  val io = IO(new Bundle {
    val op   = Input(UInt(3.W))
    val a    = Input(UInt(width.W))
    val b    = Input(UInt(width.W))
    val y    = Output(UInt(width.W))
    val zero = Output(Bool())
  })

  val shiftBits = log2Ceil(width)
  val sum  = io.a + io.b
  val diff = io.a - io.b
  val shl  = io.a << io.b(shiftBits - 1, 0)

  io.y := MuxLookup(io.op, 0.U(width.W))(
    Seq(
      0.U -> sum,
      1.U -> diff,
      2.U -> (io.a & io.b),
      3.U -> (io.a | io.b),
      4.U -> (io.a ^ io.b),
      5.U -> shl,
    )
  )
  io.zero := io.y === 0.U
}

/** 例子 3：把一组数加起来。
  *
  * 知识点：
  *   - Vec：硬件里的"一排同类型数据"，`Vec(n, UInt(w.W))` 就是一个 n 项的数组；
  *   - reduce：把一个二元算符折叠到整排数据上，综合出来是一棵加法树
  *     （比串行相加的延迟小，这正是并行硬件擅长的事）；
  *   - 位宽增长：`+&` 会保留进位，所以输出要比输入宽。
  *
  * 这种"一排数据折起来"的写法，后面写 CPU 时会反复用到
  * （例如比较一组请求的优先级、把多个结果归约）。
  */
class VecSum(n: Int, width: Int) extends Module {
  require(n >= 1, "至少要有一个输入")

  override def desiredName = s"vecsum_n${n}_w$width"

  // n 个数相加最多需要 ceil(log2(n+1)) 位额外空间
  val outWidth = width + log2Ceil(n + 1)

  val io = IO(new Bundle {
    val in  = Input(Vec(n, UInt(width.W)))
    val out = Output(UInt(outWidth.W))
  })

  io.out := io.in.reduce((x, y) => x +& y)
}
