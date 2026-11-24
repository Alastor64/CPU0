package partner

import _root_.Cache.CacheReplacement
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证 Dcache 替换路选择，不依赖 SRAM 和完整 Cache 状态机。 */
class CacheReplacementSpec extends AnyFlatSpec with Matchers {

  private class ReplacementHarness extends Module {
    val io = IO(new Bundle {
      val valid = Input(Vec(4, Bool()))
      val age = Input(Vec(4, UInt(2.W)))
      val chosen = Output(UInt(2.W))
    })

    io.chosen := CacheReplacement.selectVictim(io.valid, io.age)
  }

  private def pokeWays(
    dut: ReplacementHarness,
    valid: Seq[Boolean],
    age: Seq[Int]
  ): Unit = {
    for (i <- 0 until 4) {
      dut.io.valid(i).poke(valid(i).B)
      dut.io.age(i).poke(age(i).U)
    }
  }

  "CacheReplacement" should "prefer an invalid way over a lower-index LRU way" in {
    simulate(new ReplacementHarness) { dut =>
      pokeWays(dut, Seq(true, true, false, true), Seq(3, 2, 0, 1))
      dut.io.chosen.expect(2.U)
    }
  }

  it should "select the lowest-index invalid way" in {
    simulate(new ReplacementHarness) { dut =>
      pokeWays(dut, Seq(true, false, false, true), Seq(0, 2, 3, 1))
      dut.io.chosen.expect(1.U)
    }
  }

  it should "select the oldest way when every way is valid" in {
    simulate(new ReplacementHarness) { dut =>
      pokeWays(dut, Seq(true, true, true, true), Seq(1, 2, 3, 0))
      dut.io.chosen.expect(2.U)
    }
  }
}
