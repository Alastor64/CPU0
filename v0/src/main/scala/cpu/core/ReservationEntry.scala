package cpu.core

import chisel3._

/** One entry in an issue queue or reservation station.
  *
  * Operand readiness is explicit. A tag is meaningful only while the matching
  * ready bit is false.
  */
class ReservationEntry(val config: CoreConfig) extends Bundle {
  val valid      = Bool()
  val op         = OpType()
  val pc         = UInt(32.W)
  val robTag     = UInt(config.robTagBits.W)
  val rd         = UInt(5.W)
  val src1       = UInt(32.W)
  val src1Ready  = Bool()
  val src1Tag    = UInt(config.robTagBits.W)
  val src2       = UInt(32.W)
  val src2Ready  = Bool()
  val src2Tag    = UInt(config.robTagBits.W)
  val imm        = SInt(32.W)
  val writesRd   = Bool()
}
