package partner

import Executer.Mult
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证乘法器输出的低位/高 32 位选择与 ALU 约定一致。 */
class MultSpec extends AnyFlatSpec with Matchers {

  private def multiply(a: BigInt, b: BigInt, high: Boolean): BigInt = {
    var result = BigInt(0)
    simulate(new Mult) { dut =>
      dut.io.a.poke(a.U)
      dut.io.b.poke(b.U)
      dut.io.op.poke(high.B)
      dut.io.valid.poke(0.U)
      dut.io.clear_valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step(4)
      result = dut.io.c.peek().litValue
    }
    result
  }

  "Mult" should "return the low product half when op is clear" in {
    multiply(a = 1, b = 2, high = false) shouldBe BigInt(2)
  }

  it should "return the high product half when op is set" in {
    multiply(a = 1, b = 2, high = true) shouldBe BigInt(0)
  }

  it should "distinguish low and high halves across a 32-bit boundary" in {
    val a = BigInt("89abcdef", 16)
    val b = BigInt("76543210", 16)
    val product = a * b

    multiply(a, b, high = false) shouldBe (product & 0xffffffffL)
    multiply(a, b, high = true) shouldBe (product >> 32)
  }
}
