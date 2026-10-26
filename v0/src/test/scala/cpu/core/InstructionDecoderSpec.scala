package cpu.core

import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Locks down the instruction table and immediate reconstruction before the
  * decoder is connected to the pipeline.
  */
class InstructionDecoderSpec extends AnyFlatSpec with Matchers {

  private def bits(value: Int, high: Int, low: Int): Int =
    (value >> low) & ((1 << (high - low + 1)) - 1)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): Int =
    ((imm & 0xfff) << 20) |
      (rs1 << 15) |
      (funct3 << 12) |
      (rd << 7) |
      opcode

  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int, opcode: Int): Int =
    (bits(imm, 11, 5) << 25) |
      (rs2 << 20) |
      (rs1 << 15) |
      (funct3 << 12) |
      (bits(imm, 4, 0) << 7) |
      opcode

  private def bType(imm: Int, rs2: Int, rs1: Int, funct3: Int, opcode: Int): Int =
    (bits(imm, 12, 12) << 31) |
      (bits(imm, 10, 5) << 25) |
      (rs2 << 20) |
      (rs1 << 15) |
      (funct3 << 12) |
      (bits(imm, 4, 1) << 8) |
      (bits(imm, 11, 11) << 7) |
      opcode

  private def uType(imm: Int, rd: Int, opcode: Int): Int =
    (imm & 0xfffff000) | (rd << 7) | opcode

  private def jType(imm: Int, rd: Int, opcode: Int): Int =
    (bits(imm, 20, 20) << 31) |
      (bits(imm, 10, 1) << 21) |
      (bits(imm, 11, 11) << 20) |
      (bits(imm, 19, 12) << 12) |
      (rd << 7) |
      opcode

  "Rv32Decoder" should "contain every supported RV32IM encoding" in {
    Rv32Decoder.rules should have size 45

    Rv32Decoder.rules.foreach { rule =>
      Rv32Decoder.decode(rule.pattern).map(_.op) shouldBe Some(rule.op)
    }
  }

  it should "reject unsupported encodings" in {
    Rv32Decoder.decode(0) shouldBe None
    Rv32Decoder.decode(0x00000073) shouldBe None // ECALL
    Rv32Decoder.decode(0x00f0000f) shouldBe None // FENCE
  }

  it should "decode I-type immediates with sign extension" in {
    val decoded = Rv32Decoder.decode(iType(-1, rs1 = 2, funct3 = 0, rd = 1, opcode = 0x13)).get

    decoded.op shouldBe OpType.addi
    decoded.rd shouldBe 1
    decoded.rs1 shouldBe 2
    decoded.imm shouldBe -1
    decoded.writesRd shouldBe true
    decoded.usesRs2 shouldBe false
  }

  it should "decode store offsets" in {
    val decoded = Rv32Decoder.decode(sType(-8, rs2 = 3, rs1 = 4, funct3 = 2, opcode = 0x23)).get

    decoded.op shouldBe OpType.sw
    decoded.rs1 shouldBe 4
    decoded.rs2 shouldBe 3
    decoded.imm shouldBe -8
    decoded.writesRd shouldBe false
  }

  it should "decode branch offsets" in {
    val decoded = Rv32Decoder.decode(bType(-2, rs2 = 2, rs1 = 1, funct3 = 0, opcode = 0x63)).get

    decoded.op shouldBe OpType.beq
    decoded.imm shouldBe -2
    decoded.writesRd shouldBe false
  }

  it should "decode upper immediates" in {
    val decoded = Rv32Decoder.decode(uType(0x12345000, rd = 5, opcode = 0x37)).get

    decoded.op shouldBe OpType.lui
    decoded.rd shouldBe 5
    decoded.imm shouldBe BigInt("12345000", 16)
  }

  it should "decode JAL and JALR immediates differently" in {
    val jal = Rv32Decoder.decode(jType(-4, rd = 1, opcode = 0x6f)).get
    val jalr = Rv32Decoder.decode(iType(4, rs1 = 2, funct3 = 0, rd = 1, opcode = 0x67)).get

    jal.op shouldBe OpType.jal
    jal.imm shouldBe -4
    jal.usesRs1 shouldBe false

    jalr.op shouldBe OpType.jalr
    jalr.imm shouldBe 4
    jalr.usesRs1 shouldBe true
  }

  "InstructionDecoder" should "elaborate with the stable course-facing field names" in {
    val sv = ChiselStage.emitSystemVerilog(
      new InstructionDecoder,
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    )

    val compact = sv.replaceAll("\\s+", "")
    compact should include("moduleInstructionDecoder")
    compact should include("inputclock,reset,io_valid")
    compact should include("input[31:0]io_pc,io_instruction")
    compact should include("io_decoded_op")
  }
}
