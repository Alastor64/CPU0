package partner

import Config.CPUConfig.{ALU_NUM, ROBWidth, issueWidth}
import _root_.Issuer.Issuer
import _root_.Op.OP_TYPE
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证 Issuer 内部从接收 JALR 到产生重定向的完整小流程。 */
class IssuerJalrSpec extends AnyFlatSpec with Matchers {

  private def pokeIdleInputs(dut: Issuer): Unit = {
    dut.io.in_valid.poke(false.B)
    dut.io.valid_num.poke(0.U)
    dut.io.flush_valid.poke(false.B)
    dut.io.committer_space.poke(0.U)
    for (i <- 0 until issueWidth) {
      dut.io.id(i).poke(0.U)
      dut.io.cur_op(i).type_.poke(OP_TYPE.OP_NONE)
      dut.io.cur_op(i).rd.poke(0.U)
      dut.io.cur_op(i).r1.poke(0.U)
      dut.io.cur_op(i).r2.poke(0.U)
      dut.io.cur_op(i).id.poke(0.U)
      dut.io.predictor_result(i).poke(0.U)
    }
    for (i <- 0 until ALU_NUM + 2) {
      dut.io.executer_space(i).poke(0.U)
    }
    for (i <- 0 until 32) {
      dut.io.reg(i).value.poke(0.U)
      dut.io.reg(i).prod.poke((-1).S)
    }
    for (i <- 0 until ROBWidth) {
      dut.io.robs(i).id.poke(0.U)
      dut.io.robs(i).value.poke(0.U)
      dut.io.robs(i).dest.poke(0.S)
      dut.io.robs(i).type_.poke(0.S)
      dut.io.robs(i).ready.poke(false.B)
    }
  }

  private def pokeJalrInputs(
    dut:         Issuer,
    sourceValue: Int,
    sourceTag:   Int
  ): Unit = {
    pokeIdleInputs(dut)
    dut.io.in_valid.poke(true.B)
    dut.io.valid_num.poke(1.U)
    dut.io.committer_space.poke(4.U)
    for (i <- 0 until ALU_NUM + 2) {
      dut.io.executer_space(i).poke(1.U)
    }
    dut.io.id(0).poke(0x28.U)
    dut.io.cur_op(0).type_.poke(OP_TYPE.OP_JALR)
    dut.io.cur_op(0).rd.poke(1.U)
    dut.io.cur_op(0).r1.poke(1.U)
    dut.io.cur_op(0).r2.poke(20.U)
    dut.io.reg(1).value.poke(sourceValue.U)
    dut.io.reg(1).prod.poke(sourceTag.S)
  }

  private def resetIssuer(dut: Issuer): Unit = {
    pokeIdleInputs(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  "Issuer JALR" should "redirect from the old rs1 when rd aliases rs1" in {
    simulate(new Issuer) { dut =>
      resetIssuer(dut)

      // 对应 auipc ra, 0; jalr ra, 20(ra)。
      pokeJalrInputs(dut, sourceValue = 0x24, sourceTag = -1)
      dut.clock.step()
      dut.io.in_valid.poke(false.B)

      dut.io.out_pc_val_changed.expect(true.B)
      dut.io.out_new_pc_val.expect(0x38.U)
      dut.io.out_executer_RS(0).v1.expect(0x2c.U)
    }
  }

  it should "wait for a pending rs1 and then use its bypassed value" in {
    simulate(new Issuer) { dut =>
      resetIssuer(dut)

      pokeJalrInputs(dut, sourceValue = 0, sourceTag = 7)
      dut.clock.step()
      dut.io.in_valid.poke(false.B)

      dut.io.out_valid.expect(0.U)
      dut.io.out_pc_val_changed.expect(false.B)

      dut.io.robs(0).id.poke(7.U)
      dut.io.robs(0).value.poke(0x25.U)
      dut.io.robs(0).ready.poke(true.B)

      dut.io.out_pc_val_changed.expect(true.B)
      dut.io.out_new_pc_val.expect(0x38.U)
      dut.io.out_executer_RS(0).v1.expect(0x2c.U)
    }
  }
}
