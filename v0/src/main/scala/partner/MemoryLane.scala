package MemoryLane

import chisel3._
import chisel3.util.Cat

/** 32 位访存数据在 word 内的字节对齐与选择。
  *
  * 访存地址的低两位决定目标字节 lane。Cache 和 AXI 都按一个 32 位 word
  * 传递数据，因此 store 必须在写入前移位，load 必须在返回后反向移位。
  */
object MemoryLane {
  def byteOffset(addr: UInt): UInt = addr(1, 0)

  def byteShift(addr: UInt): UInt = byteOffset(addr) << 3

  /** 左移 store 数据并保留 32 位结果。
    *
    * 直接写 `(data << byteShift(addr))(31, 0)` 会让 CIRCT 先生成 63 位
    * 移位结果，再丢弃高 31 位，Verilator 会把这段中间信号报为
    * UNUSEDSIGNAL。这里按偏移直接拼接 32 位结果，硬件语义完全相同。
    */
  def alignStoreData(data: UInt, addr: UInt): UInt = {
    val aligned = VecInit(Seq(
      data,
      Cat(0.U(8.W), data(23, 0)),
      Cat(0.U(16.W), data(15, 0)),
      Cat(0.U(24.W), data(7, 0))
    ))
    aligned(byteOffset(addr))
  }

  /** 同样按字节偏移生成精确 4 位的 store mask。 */
  def alignStoreMask(mask: UInt, addr: UInt): UInt = {
    val aligned = VecInit(Seq(
      mask,
      Cat(0.U(1.W), mask(2, 0)),
      Cat(0.U(2.W), mask(1, 0)),
      Cat(0.U(3.W), mask(0))
    ))
    aligned(byteOffset(addr))
  }

  /** 将包含目标字节的完整 word 移到最低字节对齐的位置。 */
  def selectLoadWord(word: UInt, addr: UInt): UInt =
    (word >> byteShift(addr))(31, 0)
}
