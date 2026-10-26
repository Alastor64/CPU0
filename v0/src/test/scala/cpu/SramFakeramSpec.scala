package cpu

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证 Chisel 外部模块封装与课程 SRAM 接口一致。
  *
  * 这里只做 elaboration 和生成文本检查：存储体的仿真语义由课程框架提供的
  * `scripts/ram/sram_fakeram.sv` 负责。
  */
class SramFakeramSpec extends AnyFlatSpec with Matchers {

  /** 给外部 SRAM 提供实际输入，确保所有端口都进入生成结果。 */
  private class Harness extends Module {
    val en    = IO(Input(Bool()))
    val we    = IO(Input(Bool()))
    val wmask = IO(Input(UInt(4.W)))
    val addr  = IO(Input(UInt(10.W)))
    val wdata = IO(Input(UInt(32.W)))
    val rdata = IO(Output(UInt(32.W)))

    val ram = Module(new SramFakeram(depth = 1024, width = 32, writeGranularity = 8))
    ram.clk   := clock
    ram.en    := en
    ram.we    := we
    ram.wmask := wmask
    ram.addr  := addr
    ram.wdata := wdata
    rdata     := ram.rdata
  }

  "SramFakeram" should "emit the framework-compatible ports and parameters" in {
    val sv = ChiselStage.emitSystemVerilog(
      new Harness,
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    )

    val compact = sv.replaceAll("\\s+", "")

    compact should include("sram_fakeram#(")
    compact should include(".DEPTH(1024)")
    compact should include(".WIDTH(32)")
    compact should include(".WRITE_GRANULARITY(8)")
    compact should include(".clk(clock)")
    compact should include(".en(en)")
    compact should include(".we(we)")
    compact should include(".wmask(wmask)")
    compact should include(".addr(addr)")
    compact should include(".wdata(wdata)")
    compact should include(".rdata(rdata)")
  }

  it should "reject parameters that the framework cannot implement" in {
    an[IllegalArgumentException] should be thrownBy {
      ChiselStage.emitSystemVerilog(
        new Module {
          Module(new SramFakeram(depth = 1024, width = 32, writeGranularity = 7))
        }
      )
    }
  }
}
