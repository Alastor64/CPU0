package cpu.core

import chisel3._
import chisel3.util.{Cat, Fill, MuxCase}

/** Format-specific fields occupied by an encoded instruction. */
private[core] sealed trait InstructionFormat
private[core] object InstructionFormat {
  case object Register extends InstructionFormat
  case object Immediate extends InstructionFormat
  case object Store extends InstructionFormat
  case object Branch extends InstructionFormat
  case object UpperImmediate extends InstructionFormat
  case object Jump extends InstructionFormat
}

/** One legal RV32IM instruction pattern. */
private[core] final case class DecodeRule(
  mask: BigInt,
  pattern: BigInt,
  op: OpType.Type,
  format: InstructionFormat,
  writesRd: Boolean,
  usesRs1: Boolean,
  usesRs2: Boolean,
)

private[core] final case class DecodedFields(
  op: OpType.Type,
  format: InstructionFormat,
  rd: Int,
  rs1: Int,
  rs2: Int,
  imm: BigInt,
  writesRd: Boolean,
  usesRs1: Boolean,
  usesRs2: Boolean,
)

/** Table-driven RV32IM decoder shared by hardware and host-side tests. */
private[core] object Rv32Decoder {
  private val OpcodeLoad    = BigInt("0000011", 2)
  private val OpcodeOpImm   = BigInt("0010011", 2)
  private val OpcodeAuipc   = BigInt("0010111", 2)
  private val OpcodeStore   = BigInt("0100011", 2)
  private val OpcodeOp      = BigInt("0110011", 2)
  private val OpcodeLui     = BigInt("0110111", 2)
  private val OpcodeBranch  = BigInt("1100011", 2)
  private val OpcodeJalr    = BigInt("1100111", 2)
  private val OpcodeJal     = BigInt("1101111", 2)

  private val OpcodeMask = BigInt("7f", 16)
  private val Funct3Mask = BigInt("707f", 16)
  private val Funct7Mask = BigInt("fe00707f", 16)

  private def encoded(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(funct7) << 25) |
      (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) |
      BigInt(opcode)

  private def register(
    op: OpType.Type,
    funct7: Int,
    funct3: Int,
  ): DecodeRule = {
    val structuralPattern = encoded(funct7, 0, 0, funct3, 0, OpcodeOp.toInt)
    DecodeRule(
      mask = Funct7Mask,
      pattern = structuralPattern,
      op = op,
      format = InstructionFormat.Register,
      writesRd = true,
      usesRs1 = true,
      usesRs2 = true,
    )
  }

  private def immediate(
    op: OpType.Type,
    mask: BigInt,
    funct7: Int,
    funct3: Int,
    opcode: Int,
  ): DecodeRule =
    DecodeRule(
      mask = mask,
      pattern = encoded(funct7, 0, 0, funct3, 0, opcode),
      op = op,
      format = InstructionFormat.Immediate,
      writesRd = true,
      usesRs1 = true,
      usesRs2 = false,
    )

  private def load(op: OpType.Type, funct3: Int): DecodeRule =
    immediate(op, Funct3Mask, 0, funct3, OpcodeLoad.toInt)

  private def store(op: OpType.Type, funct3: Int): DecodeRule =
    DecodeRule(
      mask = Funct3Mask,
      pattern = encoded(0, 0, 0, funct3, 0, OpcodeStore.toInt),
      op = op,
      format = InstructionFormat.Store,
      writesRd = false,
      usesRs1 = true,
      usesRs2 = true,
    )

  private def branch(op: OpType.Type, funct3: Int): DecodeRule =
    DecodeRule(
      mask = Funct3Mask,
      pattern = encoded(0, 0, 0, funct3, 0, OpcodeBranch.toInt),
      op = op,
      format = InstructionFormat.Branch,
      writesRd = false,
      usesRs1 = true,
      usesRs2 = true,
    )

  private def upper(op: OpType.Type, opcode: Int): DecodeRule =
    DecodeRule(
      mask = OpcodeMask,
      pattern = BigInt(opcode),
      op = op,
      format = InstructionFormat.UpperImmediate,
      writesRd = true,
      usesRs1 = false,
      usesRs2 = false,
    )

  private def jump(op: OpType.Type, opcode: Int): DecodeRule =
    DecodeRule(
      mask = OpcodeMask,
      pattern = BigInt(opcode),
      op = op,
      format = InstructionFormat.Jump,
      writesRd = true,
      usesRs1 = false,
      usesRs2 = false,
    )

  private val funct7Base = 0
  private val funct7Alternative = 0x20
  private val funct7MulDiv = 0x01

  val rules: IndexedSeq[DecodeRule] = IndexedSeq(
    register(OpType.add, funct7Base, 0x0),
    register(OpType.sub, funct7Alternative, 0x0),
    register(OpType.sll, funct7Base, 0x1),
    register(OpType.slt, funct7Base, 0x2),
    register(OpType.sltu, funct7Base, 0x3),
    register(OpType.xor, funct7Base, 0x4),
    register(OpType.srl, funct7Base, 0x5),
    register(OpType.sra, funct7Alternative, 0x5),
    register(OpType.or, funct7Base, 0x6),
    register(OpType.and, funct7Base, 0x7),
    register(OpType.mul, funct7MulDiv, 0x0),
    register(OpType.mulh, funct7MulDiv, 0x1),
    register(OpType.mulhsu, funct7MulDiv, 0x2),
    register(OpType.mulhu, funct7MulDiv, 0x3),
    register(OpType.div, funct7MulDiv, 0x4),
    register(OpType.divu, funct7MulDiv, 0x5),
    register(OpType.rem, funct7MulDiv, 0x6),
    register(OpType.remu, funct7MulDiv, 0x7),
    immediate(OpType.addi, Funct3Mask, 0, 0x0, OpcodeOpImm.toInt),
    immediate(OpType.slti, Funct3Mask, 0, 0x2, OpcodeOpImm.toInt),
    immediate(OpType.sltiu, Funct3Mask, 0, 0x3, OpcodeOpImm.toInt),
    immediate(OpType.xori, Funct3Mask, 0, 0x4, OpcodeOpImm.toInt),
    immediate(OpType.ori, Funct3Mask, 0, 0x6, OpcodeOpImm.toInt),
    immediate(OpType.andi, Funct3Mask, 0, 0x7, OpcodeOpImm.toInt),
    immediate(OpType.slli, Funct7Mask, funct7Base, 0x1, OpcodeOpImm.toInt),
    immediate(OpType.srli, Funct7Mask, funct7Base, 0x5, OpcodeOpImm.toInt),
    immediate(OpType.srai, Funct7Mask, funct7Alternative, 0x5, OpcodeOpImm.toInt),
    load(OpType.lb, 0x0),
    load(OpType.lh, 0x1),
    load(OpType.lw, 0x2),
    load(OpType.lbu, 0x4),
    load(OpType.lhu, 0x5),
    store(OpType.sb, 0x0),
    store(OpType.sh, 0x1),
    store(OpType.sw, 0x2),
    branch(OpType.beq, 0x0),
    branch(OpType.bne, 0x1),
    branch(OpType.blt, 0x4),
    branch(OpType.bge, 0x5),
    branch(OpType.bltu, 0x6),
    branch(OpType.bgeu, 0x7),
    immediate(OpType.jalr, Funct3Mask, 0, 0x0, OpcodeJalr.toInt),
    jump(OpType.jal, OpcodeJal.toInt),
    upper(OpType.lui, OpcodeLui.toInt),
    upper(OpType.auipc, OpcodeAuipc.toInt),
  )

  private def bits(word: BigInt, high: Int, low: Int): BigInt =
    (word >> low) & ((BigInt(1) << (high - low + 1)) - 1)

  private def signExtend(value: BigInt, bits: Int, targetBits: Int = 32): BigInt = {
    val sign = BigInt(1) << (bits - 1)
    val modulus = BigInt(1) << targetBits
    val extended = if ((value & sign) != 0) value | (modulus - (BigInt(1) << bits)) else value
    if (extended.testBit(targetBits - 1)) extended - modulus else extended
  }

  private def decodeImmediate(word: BigInt, format: InstructionFormat): BigInt = format match {
    case InstructionFormat.Register        => BigInt(0)
    case InstructionFormat.Immediate       => signExtend(bits(word, 31, 20), 12)
    case InstructionFormat.Store           =>
      signExtend((bits(word, 31, 25) << 5) | bits(word, 11, 7), 12)
    case InstructionFormat.Branch          =>
      signExtend(
        (bits(word, 31, 31) << 12) |
          (bits(word, 7, 7) << 11) |
          (bits(word, 30, 25) << 5) |
          (bits(word, 11, 8) << 1),
        13,
      )
    case InstructionFormat.UpperImmediate  => signExtend(bits(word, 31, 12) << 12, 32)
    case InstructionFormat.Jump            =>
      signExtend(
        (bits(word, 31, 31) << 20) |
          (bits(word, 19, 12) << 12) |
          (bits(word, 20, 20) << 11) |
          (bits(word, 30, 21) << 1),
        21,
      )
  }

  def decode(word: BigInt): Option[DecodedFields] = {
    val normalized = word & 0xffffffffL
    rules.collectFirst {
      case rule if (normalized & rule.mask) == rule.pattern =>
        DecodedFields(
          op = rule.op,
          format = rule.format,
          rd = bits(normalized, 11, 7).toInt,
          rs1 = bits(normalized, 19, 15).toInt,
          rs2 = bits(normalized, 24, 20).toInt,
          imm = decodeImmediate(normalized, rule.format),
          writesRd = rule.writesRd,
          usesRs1 = rule.usesRs1,
          usesRs2 = rule.usesRs2,
        )
    }
  }
}

/** A single-lane combinational RV32IM decoder.
  *
  * Multi-issue integration will instantiate one lane per issue width and retain
  * the same decoding table, so all lanes stay behaviorally identical.
  */
class InstructionDecoder extends Module {
  val io = IO(new Bundle {
    val valid       = Input(Bool())
    val pc          = Input(UInt(32.W))
    val instruction = Input(UInt(32.W))
    val decoded     = Output(new DecodedInstruction)
  })

  private val instruction = io.instruction

  private val immediateI = io.instruction(31, 20).asSInt.pad(32)
  private val immediateS =
    Cat(io.instruction(31, 25), io.instruction(11, 7)).asSInt.pad(32)
  private val immediateB =
    Cat(
      Fill(19, io.instruction(31)),
      io.instruction(31),
      io.instruction(7),
      io.instruction(30, 25),
      io.instruction(11, 8),
      0.U(1.W),
    ).asSInt
  private val immediateU = Cat(io.instruction(31, 12), 0.U(12.W)).asSInt
  private val immediateJ =
    Cat(
      Fill(11, io.instruction(31)),
      io.instruction(31),
      io.instruction(19, 12),
      io.instruction(20),
      io.instruction(30, 21),
      0.U(1.W),
    ).asSInt

  private val matches = Rv32Decoder.rules.map { rule =>
    (io.instruction & rule.mask.U(32.W)) === rule.pattern.U(32.W)
  }
  private val hit = VecInit(matches).asUInt.orR
  private val immediateForFormat: InstructionFormat => SInt = {
    case InstructionFormat.Register       => 0.S(32.W)
    case InstructionFormat.Immediate      => immediateI
    case InstructionFormat.Store          => immediateS
    case InstructionFormat.Branch         => immediateB
    case InstructionFormat.UpperImmediate => immediateU
    case InstructionFormat.Jump           => immediateJ
  }

  io.decoded := 0.U.asTypeOf(new DecodedInstruction)
  io.decoded.valid := io.valid && hit
  io.decoded.pc := io.pc
  io.decoded.rd := instruction(11, 7)
  io.decoded.rs1 := instruction(19, 15)
  io.decoded.rs2 := instruction(24, 20)
  io.decoded.imm := MuxCase(
    0.S(32.W),
    Rv32Decoder.rules.zip(matches).map { case (rule, matched) =>
      matched -> immediateForFormat(rule.format)
    },
  )

  for ((rule, matched) <- Rv32Decoder.rules.zip(matches)) {
    when(matched) {
      io.decoded.op := rule.op
      io.decoded.writesRd := rule.writesRd.B
      io.decoded.usesRs1 := rule.usesRs1.B
      io.decoded.usesRs2 := rule.usesRs2.B
    }
  }
}
