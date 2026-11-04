package partner

import _root_.Decoder.Decoder
import _root_.Issuer.Issuer
import _root_.Op.OP_TYPE
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证 Decoder 与 Issuer 之间的整组 valid/ready 语义。 */
class PipelineFlowControlSpec extends AnyFlatSpec with Matchers {

  private def pokeDecoderInputs(
    dut:      Decoder,
    inValid:  Boolean,
    inNum:    Int,
    inIds:    Seq[Int],
    opcodes:  Seq[Int],
    outReady: Boolean,
    flush:    Boolean = false
  ): Unit = {
    dut.io.in_valid.poke(inValid.B)
    dut.io.valid_num.poke(inNum.U)
    dut.io.out_ready.poke(outReady.B)
    dut.io.flush_valid.poke(flush.B)
    for (i <- 0 until 4) {
      dut.io.id(i).poke(inIds.lift(i).getOrElse(0).U)
      dut.io.opcode(i).poke(opcodes.lift(i).getOrElse(0).U)
    }
  }

  private def pokeIssuerInputs(
    dut:    Issuer,
    inValid: Boolean,
    inNum:   Int,
    flush:   Boolean = false
  ): Unit = {
    dut.io.in_valid.poke(inValid.B)
    dut.io.valid_num.poke(inNum.U)
    dut.io.flush_valid.poke(flush.B)
    dut.io.committer_space.poke(0.U) // 禁止出队，单独观察入队空间。
    for (i <- 0 until 4) {
      dut.io.id(i).poke(i.U)
      dut.io.cur_op(i).type_.poke(OP_TYPE.OP_ADD)
      dut.io.cur_op(i).rd.poke(0.U)
      dut.io.cur_op(i).r1.poke(0.U)
      dut.io.cur_op(i).r2.poke(0.U)
      dut.io.cur_op(i).id.poke(0.U)
      dut.io.predictor_result(i).poke(0.U)
    }
    for (i <- 0 until 6) {
      dut.io.executer_space(i).poke(0.U)
    }
    for (i <- 0 until 32) {
      dut.io.reg(i).value.poke(0.U)
      dut.io.reg(i).prod.poke((-1).S)
    }
    for (i <- 0 until 16) {
      dut.io.robs(i).id.poke(0.U)
      dut.io.robs(i).value.poke(0.U)
      dut.io.robs(i).dest.poke(0.S)
      dut.io.robs(i).type_.poke(0.S)
      dut.io.robs(i).ready.poke(false.B)
    }
  }

  "Issuer" should "reject a whole bundle when its reserved ring has insufficient space" in {
    simulate(new Issuer) { dut =>
      pokeIssuerInputs(dut, inValid = false, inNum = 0)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeIssuerInputs(dut, inValid = true, inNum = 4)
      dut.io.in_ready.expect(true.B)
      dut.clock.step()
      dut.clock.step()
      dut.clock.step()
      dut.io.in_ready.expect(false.B, "15 项队列只剩 3 项时不能接收 4 项")

      dut.clock.step()
      dut.io.in_ready.expect(false.B, "失败的握手不能改变写指针")

      pokeIssuerInputs(dut, inValid = true, inNum = 3)
      dut.io.in_ready.expect(true.B)
      dut.clock.step()
      dut.io.in_ready.expect(false.B, "队列满后不能继续接收")
    }
  }

  it should "clear readiness, output valid, and occupancy on flush" in {
    simulate(new Issuer) { dut =>
      pokeIssuerInputs(dut, inValid = false, inNum = 0)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeIssuerInputs(dut, inValid = true, inNum = 4)
      dut.clock.step()
      dut.io.out_valid.expect(0.U)

      pokeIssuerInputs(dut, inValid = true, inNum = 4, flush = true)
      dut.io.in_ready.expect(false.B)
      dut.io.out_valid.expect(0.U)
      dut.clock.step()

      pokeIssuerInputs(dut, inValid = false, inNum = 0)
      dut.io.in_ready.expect(true.B)
      dut.io.out_valid.expect(0.U)
    }
  }

  "Decoder" should "buffer one group and backpressure only while occupied" in {
    simulate(new Decoder) { dut =>
      pokeDecoderInputs(
        dut,
        inValid = false,
        inNum = 0,
        inIds = Seq.empty,
        opcodes = Seq.empty,
        outReady = false
      )
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in_ready.expect(true.B, "空 Decoder 必须能预取一条事务")
      pokeDecoderInputs(
        dut,
        inValid = true,
        inNum = 2,
        inIds = Seq(0x100, 0x104),
        opcodes = Seq(0x00100093, 0x00200113),
        outReady = false
      )
      dut.clock.step()

      dut.io.out_valid.expect(true.B)
      dut.io.out_num.expect(2.U)
      dut.io.out_id(0).expect(0x100.U)
      dut.io.out_id(1).expect(0x104.U)
      dut.io.out_op(0).type_.peek().litValue shouldBe OP_TYPE.OP_ADDI.litValue
      dut.io.in_ready.expect(false.B, "已占用且下游未 ready 时必须反压取指")

      pokeDecoderInputs(
        dut,
        inValid = false,
        inNum = 0,
        inIds = Seq.empty,
        opcodes = Seq.empty,
        outReady = false
      )
      dut.clock.step()
      dut.io.out_id(0).expect(0x100.U)

      pokeDecoderInputs(
        dut,
        inValid = true,
        inNum = 1,
        inIds = Seq(0x200),
        opcodes = Seq(0x00300193),
        outReady = true
      )
      dut.io.in_ready.expect(true.B, "下游接收旧数据时，本周期可替换缓冲内容")
      dut.clock.step()
      dut.io.out_valid.expect(true.B)
      dut.io.out_num.expect(1.U)
      dut.io.out_id(0).expect(0x200.U)
    }
  }

  it should "flush the buffered group and suppress output valid" in {
    simulate(new Decoder) { dut =>
      pokeDecoderInputs(
        dut,
        inValid = false,
        inNum = 0,
        inIds = Seq.empty,
        opcodes = Seq.empty,
        outReady = false
      )
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeDecoderInputs(
        dut,
        inValid = true,
        inNum = 1,
        inIds = Seq(0x300),
        opcodes = Seq(0x00100093),
        outReady = false
      )
      dut.clock.step()
      dut.io.out_valid.expect(true.B)

      pokeDecoderInputs(
        dut,
        inValid = false,
        inNum = 0,
        inIds = Seq.empty,
        opcodes = Seq.empty,
        outReady = false,
        flush = true
      )
      dut.io.in_ready.expect(false.B)
      dut.io.out_valid.expect(false.B)
      dut.clock.step()

      pokeDecoderInputs(
        dut,
        inValid = false,
        inNum = 0,
        inIds = Seq.empty,
        opcodes = Seq.empty,
        outReady = false
      )
      dut.io.in_ready.expect(true.B)
      dut.io.out_valid.expect(false.B)
    }
  }
}
