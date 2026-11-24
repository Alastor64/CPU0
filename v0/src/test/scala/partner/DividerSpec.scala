package partner

import Executer.Divider
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证除法器在流水线延迟后的商和余数。 */
class DividerSpec extends AnyFlatSpec with Matchers {

  private def divide(a: BigInt, b: BigInt, quotient: Boolean): BigInt = {
    var result = BigInt(0)
    simulate(new Divider) { dut =>
      dut.io.a.poke(a.U)
      dut.io.b.poke(b.U)
      dut.io.op.poke(quotient.B)
      dut.io.valid.poke(0.U)
      dut.io.clear_valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.valid.poke(1.U)
      dut.clock.step()
      dut.io.valid.poke(0.U)

      var ready = false
      for (_ <- 0 until 40) {
        if (dut.io.ready.peek().litValue != 0) {
          ready = true
          result = dut.io.c.peek().litValue
        }
        dut.clock.step()
      }
      ready shouldBe true
    }
    result
  }

  "Divider" should "produce the unsigned quotient" in {
    divide(100, 7, quotient = true) shouldBe BigInt(14)
  }

  it should "produce the unsigned remainder" in {
    divide(100, 7, quotient = false) shouldBe BigInt(2)
  }

  it should "handle operands with the high bit set" in {
    val a = BigInt("f1234567", 16)
    val b = BigInt(7)
    divide(a, b, quotient = true) shouldBe (a / b)
    divide(a, b, quotient = false) shouldBe (a % b)
    divide(a, BigInt(253), quotient = true) shouldBe (a / 253)
    divide(a, BigInt(253), quotient = false) shouldBe (a % 253)
  }
}
