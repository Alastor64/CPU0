package cpu.core

import chisel3.util.log2Ceil

/** Parameters shared by the reorder-buffer based core.
  *
  * The partner source exposed these values as mutable global constants. Keeping
  * them in an immutable configuration object makes every structural assumption
  * explicit and allows modules to receive a complete configuration instead of
  * importing hidden globals.
  */
final case class CoreConfig(
  issueWidth: Int = 4,
  robDepth: Int = 16,
  reservationDepth: Int = 16,
  aluCount: Int = 4,
  fetchQueueDepth: Int = 16,
) {
  require(issueWidth > 0, s"issueWidth must be positive, got $issueWidth")
  require(robDepth > 0, s"robDepth must be positive, got $robDepth")
  require(reservationDepth > 0, s"reservationDepth must be positive, got $reservationDepth")
  require(aluCount > 0, s"aluCount must be positive, got $aluCount")
  require(fetchQueueDepth > 0, s"fetchQueueDepth must be positive, got $fetchQueueDepth")

  val robTagBits: Int = math.max(1, log2Ceil(robDepth))
  val reservationIndexBits: Int = math.max(1, log2Ceil(reservationDepth))
}
