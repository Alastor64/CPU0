package partner

import Config.CPUConfig.{ALU_NUM, ROBWidth, issueWidth}
import _root_.Issuer.Issuer
import _root_.Op.OP_TYPE
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证写 x0 不会创建 producer tag，也不会阻塞后续读取 x0 的指令。 */
class IssuerX0Spec extends AnyFlatSpec with Matchers {

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
      dut.io.robs(i).target.poke(0.U)
      dut.io.robs(i).is_jalr.poke(false.B)
      dut.io.robs(i).ready.poke(false.B)
    }
  }

  private def pokeAddImmediate(
    dut: Issuer,
    slot: Int,
    pc: Int,
    rd: Int,
    rs1: Int,
    immediate: Int
  ): Unit = {
    dut.io.id(slot).poke(pc.U)
    dut.io.cur_op(slot).type_.poke(OP_TYPE.OP_ADDI)
    dut.io.cur_op(slot).rd.poke(rd.U)
    dut.io.cur_op(slot).r1.poke(rs1.U)
    dut.io.cur_op(slot).r2.poke(immediate.U)
  }

  "Issuer" should "not create a producer tag for x0" in {
    simulate(new Issuer) { dut =>
      pokeIdleInputs(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // 前一条写 x0，后一条立即读取 x0，覆盖最直接的伪依赖路径。
      pokeIdleInputs(dut)
      dut.io.in_valid.poke(true.B)
      dut.io.valid_num.poke(2.U)
      dut.io.committer_space.poke(4.U)
      for (i <- 0 until ALU_NUM + 2) {
        dut.io.executer_space(i).poke(2.U)
      }
      pokeAddImmediate(dut, slot = 0, pc = 0x100, rd = 0, rs1 = 0, immediate = 1)
      pokeAddImmediate(dut, slot = 1, pc = 0x104, rd = 1, rs1 = 0, immediate = 2)
      dut.clock.step()
      dut.io.in_valid.poke(false.B)

      dut.io.out_valid.expect(2.U)
      dut.io.out_prod(0).expect((-1).S)
      dut.io.out_prod_valid(0).expect(false.B)
      dut.io.out_executer_RS(1).q1.expect((-1).S)
      dut.io.out_executer_RS(1).v1.expect(0.U)
    }
  }
}
