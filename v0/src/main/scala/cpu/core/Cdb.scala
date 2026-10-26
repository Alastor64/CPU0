package cpu.core

import chisel3._

/** A writeback result broadcast to reservation stations and the ROB. */
class CdbEntry(val config: CoreConfig) extends Bundle {
  val valid  = Bool()
  val robTag = UInt(config.robTagBits.W)
  val rd     = UInt(5.W)
  val value  = UInt(32.W)
}
