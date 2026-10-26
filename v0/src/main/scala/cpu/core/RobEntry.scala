package cpu.core

import chisel3._

/** A reorder-buffer entry.
  *
  * Store payload and branch outcome are kept in separate fields so commit no
  * longer has to overload the partner's `dest`, `value`, and `type` fields.
  */
class RobEntry(val config: CoreConfig) extends Bundle {
  val valid       = Bool()
  val ready       = Bool()
  val robTag      = UInt(config.robTagBits.W)
  val op          = OpType()
  val rd          = UInt(5.W)
  val value       = UInt(32.W)
  val writesRd    = Bool()
  val isStore     = Bool()
  val storeAddr   = UInt(32.W)
  val storeData   = UInt(32.W)
  val storeBytes  = UInt(3.W)
  val isBranch    = Bool()
  val branchTaken = Bool()
  val branchTarget = UInt(32.W)
}
