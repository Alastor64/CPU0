package partner

import Config.CPUConfig.{ALU_NUM, RSWidth, issueWidth}
import _root_.Executer.ALU
import _root_.Op.OP_TYPE
import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 测试专用的 ALU，仅把保留站内部状态引出为可观察端口。 */
private class ProbeALU extends ALU(0) {
  val probe = IO(Output(new Bundle {
    val freeCount = UInt(log2Ceil(RSWidth + 1).W)
    val valid = Vec(RSWidth, Bool())
    val ids = Vec(RSWidth, UInt(32.W))
    val selected = Bool()
    val selectedId = UInt(32.W)
  }))
  probe.freeCount := freeCount
  probe.valid := valid
  for (i <- 0 until RSWidth) {
    probe.ids(i) := rss(i).id
  }
  probe.selected := start_process
  probe.selectedId := temp_rs.id
}

/** 验证 bitmap 保留站的空间管理、最老优先和 flush 全量恢复。 */
class ReservationStationSpec extends AnyFlatSpec with Matchers {

  private def pokeIdle(dut: ProbeALU): Unit = {
    dut.io.valid_num.poke(0.U)
    dut.io.clear_valid.poke(false.B)
    dut.io.clear_id.poke(0.S)
    for (i <- 0 until issueWidth) {
      dut.io.rs(i).type_.poke(OP_TYPE.OP_NONE)
      dut.io.rs(i).v1.poke(0.U)
      dut.io.rs(i).v2.poke(0.U)
      dut.io.rs(i).q1.poke((-1).S)
      dut.io.rs(i).q2.poke((-1).S)
      dut.io.rs(i).dest.poke(0.S)
      dut.io.rs(i).id.poke(0.U)
      dut.io.rs(i).is_halt.poke(false.B)
    }
    for (i <- 0 until ALU_NUM + 2) {
      dut.io.cdbs(i).id.poke(0.U)
      dut.io.cdbs(i).dest.poke(0.S)
      dut.io.cdbs(i).value.poke(0.U)
    }
    dut.aluIO.mult_valid.poke(0.U)
    dut.aluIO.mult_res.poke(0.U)
    dut.aluIO.mult_received.poke(0.U)
    dut.aluIO.div_valid.poke(0.U)
    dut.aluIO.div_res.poke(0.U)
    dut.aluIO.div_received.poke(0.U)
    dut.reset.poke(false.B)
  }

  private def pokeEntry(
    dut:   ProbeALU,
    slot:  Int,
    id:    BigInt,
    q1:    BigInt = -1,
    value: Int = 0
  ): Unit = {
    dut.io.rs(slot).type_.poke(OP_TYPE.OP_ADD)
    dut.io.rs(slot).v1.poke(value.U)
    dut.io.rs(slot).v2.poke(1.U)
    dut.io.rs(slot).q1.poke(q1.S)
    dut.io.rs(slot).q2.poke((-1).S)
    dut.io.rs(slot).dest.poke(1.S)
    dut.io.rs(slot).id.poke(id.U)
    dut.io.rs(slot).is_halt.poke(false.B)
  }

  private def resetDut(dut: ProbeALU): Unit = {
    pokeIdle(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  "Reservation station" should "use all 16 bitmap slots and report backpressure" in {
    simulate(new ProbeALU) { dut =>
      resetDut(dut)
      dut.probe.freeCount.expect(16.U)
      dut.io.out_space.expect(16.U)
      dut.io.in_ready.expect(true.B)

      for (round <- 0 until 4) {
        pokeIdle(dut)
        dut.io.valid_num.poke(4.U)
        for (i <- 0 until 4) {
          pokeEntry(dut, i, id = round * 4 + i, q1 = 7)
        }
        dut.io.in_ready.expect(true.B)
        dut.clock.step()
      }

      dut.probe.freeCount.expect(0.U)
      dut.io.out_space.expect(0.U)
      dut.io.in_ready.expect(false.B)
      for (i <- 0 until RSWidth) {
        dut.probe.valid(i).expect(true.B)
      }
    }
  }

  it should "release a selected slot without allowing same-cycle reuse" in {
    simulate(new ProbeALU) { dut =>
      resetDut(dut)

      pokeIdle(dut)
      pokeEntry(dut, 0, id = 10)
      dut.io.valid_num.poke(1.U)
      dut.clock.step()
      dut.probe.freeCount.expect(15.U)
      dut.probe.valid(0).expect(true.B)
      dut.probe.ids(0).expect(10.U)
      dut.probe.selected.expect(true.B)
      dut.probe.selectedId.expect(10.U)

      // 老项在本拍发射；新项只能使用旧 bitmap 中的空位，不能复用刚弹出的槽。
      pokeIdle(dut)
      pokeEntry(dut, 0, id = 20)
      dut.io.valid_num.poke(1.U)
      dut.clock.step()
      dut.probe.freeCount.expect(15.U)
      dut.probe.valid(0).expect(false.B)
      dut.probe.valid(1).expect(true.B)
      dut.probe.ids(1).expect(20.U)
      dut.probe.selectedId.expect(20.U)
    }
  }

  it should "choose the oldest ready entry using wrap-safe ID comparison" in {
    simulate(new ProbeALU) { dut =>
      resetDut(dut)

      pokeIdle(dut)
      pokeEntry(dut, 0, id = BigInt("fffffff0", 16))
      pokeEntry(dut, 1, id = 5)
      dut.io.valid_num.poke(2.U)
      dut.clock.step()

      pokeIdle(dut)
      dut.probe.selected.expect(true.B)
      dut.probe.selectedId.expect(BigInt("fffffff0", 16).U)
      dut.clock.step()
      dut.probe.valid(0).expect(false.B)
      dut.probe.valid(1).expect(true.B)
      dut.probe.selectedId.expect(5.U)
    }
  }

  it should "wake an entry from the CDB and restore all slots on flush" in {
    simulate(new ProbeALU) { dut =>
      resetDut(dut)

      pokeIdle(dut)
      pokeEntry(dut, 0, id = 42, q1 = 7)
      dut.io.valid_num.poke(1.U)
      dut.clock.step()

      pokeIdle(dut)
      dut.io.cdbs(0).id.poke(7.U)
      dut.io.cdbs(0).value.poke(123.U)
      dut.clock.step()

      pokeIdle(dut)
      dut.probe.selected.expect(true.B)
      dut.probe.selectedId.expect(42.U)
      dut.clock.step()
      dut.probe.valid(0).expect(false.B)

      pokeIdle(dut)
      pokeEntry(dut, 0, id = 99, q1 = 8)
      dut.io.valid_num.poke(1.U)
      dut.clock.step()
      dut.probe.freeCount.expect(15.U)

      pokeIdle(dut)
      dut.io.clear_valid.poke(true.B)
      dut.clock.step()

      dut.probe.freeCount.expect(16.U)
      for (i <- 0 until RSWidth) {
        dut.probe.valid(i).expect(false.B)
      }
    }
  }
}
