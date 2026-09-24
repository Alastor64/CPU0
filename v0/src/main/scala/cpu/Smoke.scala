package cpu

import chisel3._
// _root_ 前缀是为了和 chisel3.util.circt 下的同名类区分开
import _root_.circt.stage.ChiselStage

/** 最小冒烟例子：只用来验证「Chisel 代码 -> SystemVerilog」这条链路是通的。
  * 真正的 CPU 代码写起来之后，这个文件就可以删掉。
  */
class Counter extends Module {
  val io = IO(new Bundle {
    val q = Output(UInt(8.W))
  })

  val r = RegInit(0.U(8.W))
  r := r + 1.U
  io.q := r
}

object Smoke extends App {
  println(
    ChiselStage.emitSystemVerilog(
      gen = new Counter,
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    )
  )
}
