package cpu.core

import chisel3._

/** A reorder-buffer entry that owns both rename state and wake-up operands.
  *
  * The 32-bit tag is monotonic so a stale producer tag can never accidentally
  * match a reallocated ROB slot. `ready` means the execution result is present;
  * `issued` prevents the same instruction from being sent twice while a
  * functional unit is occupied.
  */
class CoreRobEntry extends Bundle {
  val valid = Bool()
  val ready = Bool()
  val issued = Bool()
  val tag = UInt(32.W)
  val pc = UInt(32.W)
  val op = OpType()
  val rd = UInt(5.W)
  val imm = SInt(32.W)
  val writesRd = Bool()

  val rs1Ready = Bool()
  val rs1Tag = UInt(32.W)
  val rs1Value = UInt(32.W)
  val rs2Ready = Bool()
  val rs2Tag = UInt(32.W)
  val rs2Value = UInt(32.W)

  val value = UInt(32.W)
  val isStore = Bool()
  val storeBytes = UInt(3.W)
  val storeAddr = UInt(32.W)
  val storeData = UInt(32.W)
  val isControl = Bool()
  val branchTaken = Bool()
  val branchTarget = UInt(32.W)
}
