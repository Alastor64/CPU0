package partner

import _root_.FetchControl.FetchControl
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Verify fetch-time control predecode and instruction-prefix selection. */
class FetchControlSpec extends AnyFlatSpec with Matchers {
  private val Addi = 0x00100093
  private val Beq8 = 0x00000463
  private val Beq16 = 0x00000863
  private val Jal8 = 0x0080006f

  private def check(
    instructions: Seq[Int],
    hits:          Seq[Boolean],
    predictions:   Seq[Int],
    expectedValid: Seq[Boolean],
    expectedNum:   Int,
    expectedRedirect: Option[Int] = None
  ): Unit = simulate(new FetchControl) { dut =>
    for (i <- 0 until 4) {
      dut.io.pcs(i).poke((0x100 + 4 * i).U)
      dut.io.instructions(i).poke(instructions.lift(i).getOrElse(Addi).U)
      dut.io.hits(i).poke(hits.lift(i).getOrElse(true).B)
      dut.io.predictions(i).poke((predictions.lift(i).getOrElse(0) != 0).B)
    }

    for (i <- 0 until 4) {
      dut.io.validMask(i).expect(expectedValid(i).B)
    }
    dut.io.validNum.expect(expectedNum.U)
    expectedRedirect match {
      case Some(target) =>
        dut.io.redirect.expect(true.B)
        dut.io.redirectTarget.expect(target.U)
      case None =>
        dut.io.redirect.expect(false.B)
        dut.io.redirectTarget.expect(0.U)
    }
  }

  "FetchControl" should "stop after a predicted-taken branch" in {
    check(
      instructions = Seq(Addi, Addi, Beq16, Addi),
      hits = Seq(true, true, true, true),
      predictions = Seq(0, 0, 1, 0),
      expectedValid = Seq(true, true, true, false),
      expectedNum = 3,
      expectedRedirect = Some(0x118),
    )
  }

  it should "continue after a not-taken branch and stop at a later JAL" in {
    check(
      instructions = Seq(Beq8, Addi, Jal8, Addi),
      hits = Seq(true, true, true, true),
      predictions = Seq(0, 1, 1, 1),
      expectedValid = Seq(true, true, true, false),
      expectedNum = 3,
      expectedRedirect = Some(0x110),
    )
  }

  it should "ignore a taken prediction on a non-control instruction" in {
    check(
      instructions = Seq(Addi, Addi, Addi, Addi),
      hits = Seq(true, true, true, true),
      predictions = Seq(1, 1, 1, 1),
      expectedValid = Seq(true, true, true, true),
      expectedNum = 4,
    )
  }

  it should "stop at the first I-cache miss" in {
    check(
      instructions = Seq(Addi, Addi, Addi, Addi),
      hits = Seq(true, false, true, true),
      predictions = Seq.fill(4)(0),
      expectedValid = Seq(true, false, false, false),
      expectedNum = 1,
    )
  }

}
