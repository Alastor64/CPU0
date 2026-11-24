package partner

import Config.CPUConfig.{ALU_NUM, issueWidth}
import _root_.Committer.Committer
import _root_.MemoryLane.MemoryLane
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 直接验证 word 内字节 lane 的转换，不依赖 Cache 状态机。 */
class MemoryLaneSpec extends AnyFlatSpec with Matchers {

  private class LoadLaneHarness extends Module {
    val io = IO(new Bundle {
      val word = Input(UInt(32.W))
      val addr = Input(UInt(32.W))
      val out = Output(UInt(32.W))
    })

    io.out := MemoryLane.selectLoadWord(io.word, io.addr)
  }

  private def pokeCommitterIdle(dut: Committer): Unit = {
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
  }

  private def commitStoreRequest(
    storeBytes: Int,
    address: BigInt,
    value: BigInt
  ): (BigInt, BigInt) = {
    var result = (BigInt(0), BigInt(0))
    simulate(new Committer) { dut =>
      pokeCommitterIdle(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.valid_num.poke(1.U)
      dut.io.in_robs(0).id.poke(1.U)
      dut.io.in_robs(0).value.poke(value.U)
      dut.io.in_robs(0).dest.poke(address.S)
      dut.io.in_robs(0).type_.poke(storeBytes.S)
      dut.io.in_robs(0).ready.poke(true.B)
      dut.clock.step()

      dut.io.out_store_valid.expect(true.B)
      dut.io.out_store_addr.expect(address.U)
      result = (
        dut.io.out_store_value.peek().litValue,
        dut.io.out_store_mask.peek().litValue
      )
    }
    result
  }

  "MemoryLane" should "place stores in the lane selected by addr[1:0]" in {
    val value = BigInt("a1b2c3d4", 16)
    val cases = Seq(
      (1, 0, BigInt("00000001", 16)),
      (1, 1, BigInt("00000002", 16)),
      (1, 2, BigInt("00000004", 16)),
      (1, 3, BigInt("00000008", 16)),
      (2, 0, BigInt("00000003", 16)),
      (2, 2, BigInt("0000000c", 16)),
      (4, 0, BigInt("0000000f", 16))
    )

    for ((bytes, offset, expectedMask) <- cases) {
      val (data, mask) =
        commitStoreRequest(bytes, BigInt(0x1000 + offset), value)
      data shouldBe ((value << (offset * 8)) & BigInt("ffffffff", 16))
      mask shouldBe expectedMask
    }
  }

  it should "select the addressed byte from a returned word" in {
    simulate(new LoadLaneHarness) { dut =>
      val word = BigInt("44332211", 16)
      dut.io.word.poke(word.U)
      for (offset <- 0 until 4) {
        dut.io.addr.poke(BigInt(0x1000 + offset).U)
        val selected = dut.io.out.peek().litValue
        ((selected & BigInt("ff", 16))) shouldBe
          ((word >> (offset * 8)) & BigInt("ff", 16))
      }
    }
  }
}
