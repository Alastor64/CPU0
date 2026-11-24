package Cache

import chisel3._
import chisel3.util.log2Ceil

/** Cache 替换路选择。
  *
  * 无效路优先，否则选择 age 最大的一条有效路。age=0 表示最近使用，
  * age 越大表示越久未使用。
  */
object CacheReplacement {
  private def firstSelected(candidates: Seq[Bool], indexBits: Int): UInt =
    candidates.zipWithIndex.reverse.foldLeft(0.U(indexBits.W)) {
      case (selected, (candidate, index)) =>
        Mux(candidate, index.U(indexBits.W), selected)
    }

  def selectVictim(valid: Vec[Bool], age: Vec[UInt]): UInt = {
    val wayCount = valid.length
    require(wayCount > 1 && wayCount == age.length)
    require(wayCount == (1 << log2Ceil(wayCount)))

    val indexBits = log2Ceil(wayCount)
    val invalid = valid.map(!_)
    val hasInvalid = invalid.reduce(_ || _)
    val invalidChoice = firstSelected(invalid, indexBits)
    val lruChoice =
      firstSelected(age.map(_ === (wayCount - 1).U), indexBits)

    Mux(hasInvalid, invalidChoice, lruChoice)
  }
}
