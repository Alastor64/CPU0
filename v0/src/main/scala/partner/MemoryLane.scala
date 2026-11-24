package MemoryLane

import chisel3._

/** 32 位访存数据在 word 内的字节对齐与选择。
  *
  * 访存地址的低两位决定目标字节 lane。Cache 和 AXI 都按一个 32 位 word
  * 传递数据，因此 store 必须在写入前移位，load 必须在返回后反向移位。
  */
object MemoryLane {
  def byteOffset(addr: UInt): UInt = addr(1, 0)

  def byteShift(addr: UInt): UInt = byteOffset(addr) << 3

  def alignStoreData(data: UInt, addr: UInt): UInt =
    (data << byteShift(addr))(31, 0)

  def alignStoreMask(mask: UInt, addr: UInt): UInt =
    (mask << byteOffset(addr))(3, 0)

  /** 将包含目标字节的完整 word 移到最低字节对齐的位置。 */
  def selectLoadWord(word: UInt, addr: UInt): UInt =
    (word >> byteShift(addr))(31, 0)
}
