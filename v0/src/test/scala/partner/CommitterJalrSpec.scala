package partner

import Config.CPUConfig.{ALU_NUM, issueWidth}
import _root_.Committer.Committer
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证 JALR 在 commit 点同时写回返回地址并重定向。 */
class CommitterJalrSpec extends AnyFlatSpec with Matchers {

  private def pokeIdle(dut: Committer): Unit = {
    dut.io.valid_num.poke(0.U)
    dut.io.clear_valid.poke(false.B)
    dut.io.store_received.poke(false.B)
    for (i <- 0 until issueWidth) {
      dut.io.in_robs(i).id.poke(0.U)
      dut.io.in_robs(i).value.poke(0.U)
      dut.io.in_robs(i).dest.poke(0.S)
      dut.io.in_robs(i).type_.poke(0.S)
      dut.io.in_robs(i).target.poke(0.U)
      dut.io.in_robs(i).is_jalr.poke(false.B)
      dut.io.in_robs(i).ready.poke(false.B)
    }
    for (i <- 0 until ALU_NUM + 2) {
      dut.io.cdbs(i).id.poke(0.U)
      dut.io.cdbs(i).dest.poke(0.S)
      dut.io.cdbs(i).value.poke(0.U)
    }
    dut.reset.poke(false.B)
  }

  private def resetDut(dut: Committer): Unit = {
    pokeIdle(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  "Committer JALR" should "write rd and redirect when the JALR commits" in {
    simulate(new Committer) { dut =>
      resetDut(dut)

      pokeIdle(dut)
      dut.io.valid_num.poke(1.U)
      dut.io.in_robs(0).id.poke(9.U)
      dut.io.in_robs(0).value.poke(0x2c.U)
      dut.io.in_robs(0).dest.poke(1.S)
      dut.io.in_robs(0).type_.poke(0.S)
      dut.io.in_robs(0).target.poke(0x38.U)
      dut.io.in_robs(0).is_jalr.poke(true.B)
      dut.io.in_robs(0).ready.poke(true.B)
      dut.clock.step()

      dut.io.out_reg_valid.expect(true.B)
      dut.io.out_reg_id.expect(1.U)
      dut.io.out_reg_val.expect(0x2c.U)
      dut.io.out_reg_prod.expect(9.U)
      dut.io.out_flush.expect(true.B)
      dut.io.out_flushval.expect(9.U)
      dut.io.out_newaddr.expect(0x38.U)
    }
  }

  it should "redirect without a register write when rd is zero" in {
    simulate(new Committer) { dut =>
      resetDut(dut)

      pokeIdle(dut)
      dut.io.valid_num.poke(1.U)
      dut.io.in_robs(0).id.poke(11.U)
      dut.io.in_robs(0).value.poke(0x104.U)
      dut.io.in_robs(0).dest.poke(0.S)
      dut.io.in_robs(0).target.poke(0x200.U)
      dut.io.in_robs(0).is_jalr.poke(true.B)
      dut.io.in_robs(0).ready.poke(true.B)
      dut.clock.step()

      dut.io.out_reg_valid.expect(false.B)
      dut.io.out_flush.expect(true.B)
      dut.io.out_flushval.expect(11.U)
      dut.io.out_newaddr.expect(0x200.U)
    }
  }
}
