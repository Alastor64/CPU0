package cpu.core

import chisel3._

/** Every user-mode operation required by the course's RV32IM target.
  *
  * Privileged and optional instructions are absent on purpose. Unsupported or
  * malformed encodings decode to [[OpType.illegal]].
  */
object OpType extends ChiselEnum {
  val illegal,
    add,
    sub,
    sll,
    slt,
    sltu,
    xor,
    srl,
    sra,
    or,
    and,
    addi,
    slti,
    sltiu,
    xori,
    ori,
    andi,
    slli,
    srli,
    srai,
    lb,
    lbu,
    lh,
    lhu,
    lw,
    sb,
    sh,
    sw,
    beq,
    bne,
    blt,
    bge,
    bltu,
    bgeu,
    jal,
    jalr,
    lui,
    auipc,
    mul,
    mulh,
    mulhsu,
    mulhu,
    div,
    divu,
    rem,
    remu = Value
}

/** One decoded instruction.
  *
  * `rd`, `rs1`, and `rs2` are always the five-bit architectural register
  * indices. `imm` is always a full 32-bit, sign-extended immediate. Consumers
  * use the validity flags instead of relying on overloaded operand fields.
  */
class DecodedInstruction extends Bundle {
  val valid    = Bool()
  val op       = OpType()
  val pc       = UInt(32.W)
  val rd       = UInt(5.W)
  val rs1      = UInt(5.W)
  val rs2      = UInt(5.W)
  val imm      = SInt(32.W)
  val writesRd = Bool()
  val usesRs1  = Bool()
  val usesRs2  = Bool()
}
