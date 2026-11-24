package FetchControl

import Config.CPUConfig.issueWidth
import chisel3._
import chisel3.util.Cat

/** Shared instruction fields needed before full decode.
  *
  * Keep the immediate extraction here so the Fetcher and Decoder cannot drift
  * apart while both consume the same instruction bits.
  */
object FetchPredecode {
  private val BranchOpcode = "b1100011".U
  private val JalOpcode = "b1101111".U

  private def signExtend(value: UInt): UInt =
    value.asSInt.pad(32).asUInt

  def branchImmediate(instruction: UInt): UInt =
    signExtend(Cat(
      instruction(31),
      instruction(7),
      instruction(30, 25),
      instruction(11, 8),
      0.U(1.W),
    ))

  def jalImmediate(instruction: UInt): UInt =
    signExtend(Cat(
      instruction(31),
      instruction(19, 12),
      instruction(20),
      instruction(30, 21),
      0.U(1.W),
    ))

  def isBranch(instruction: UInt): Bool = instruction(6, 0) === BranchOpcode
  def isJal(instruction: UInt): Bool = instruction(6, 0) === JalOpcode
  def branchTarget(pc: UInt, instruction: UInt): UInt =
    (pc.asSInt + branchImmediate(instruction).asSInt).asUInt

  def jalTarget(pc: UInt, instruction: UInt): UInt =
    (pc.asSInt + jalImmediate(instruction).asSInt).asUInt
}

/** Select the architectural instruction prefix for one I-cache fetch group.
  *
  * A conditional branch does not end the group unless it is predicted taken.
  * JAL always ends the group because its target is known from the instruction.
  * JALR keeps the existing commit-time redirect path and therefore does not
  * end the fetch group here.
  */
class FetchControl extends Module {
  val io = IO(new Bundle {
    val pcs = Input(Vec(issueWidth, UInt(32.W)))
    val instructions = Input(Vec(issueWidth, UInt(32.W)))
    val hits = Input(Vec(issueWidth, Bool()))
    // FetchControl 只需要最终的 taken/not-taken 决策；完整的预测元数据由
    // Decoder 和 Issuer 使用，不应在本模块中携带。
    val predictions = Input(Vec(issueWidth, Bool()))

    val validMask = Output(Vec(issueWidth, Bool()))
    val validNum = Output(UInt(3.W))
    val redirect = Output(Bool())
    val redirectTarget = Output(UInt(32.W))
  })

  val active = Wire(Vec(issueWidth + 1, Bool()))
  val count = Wire(Vec(issueWidth + 1, UInt(3.W)))
  active(0) := true.B
  count(0) := 0.U

  io.validMask := 0.U.asTypeOf(io.validMask)
  io.redirect := false.B
  io.redirectTarget := 0.U

  for (i <- 0 until issueWidth) {
    val instruction = io.instructions(i)
    val slotValid = active(i) && io.hits(i)
    val isBranch = FetchPredecode.isBranch(instruction)
    val isJal = FetchPredecode.isJal(instruction)
    val branchRedirect = isBranch && io.predictions(i)
    val redirect = isJal || branchRedirect

    io.validMask(i) := slotValid
    count(i + 1) := count(i) + slotValid
    active(i + 1) := slotValid && !redirect

    when (slotValid && redirect) {
      io.redirect := true.B
      io.redirectTarget := Mux(
        isJal,
        FetchPredecode.jalTarget(io.pcs(i), instruction),
        FetchPredecode.branchTarget(io.pcs(i), instruction),
      )
    }
  }

  io.validNum := count(issueWidth)
}
