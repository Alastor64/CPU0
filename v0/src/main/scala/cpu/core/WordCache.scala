package cpu.core

import chisel3._
import chisel3.util._

class CacheRequest extends Bundle {
  val addr = UInt(32.W)
}

class CacheResponse extends Bundle {
  val data = UInt(32.W)
}

class CacheWrite extends Bundle {
  val valid = Bool()
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val strb = UInt(4.W)
}

/** Small direct-mapped word cache used for instruction and data accesses.
  *
  * This is intentionally simple and fully synthesizable. It provides a stable
  * request/response contract so line prefetching or set associativity can be
  * added without changing the pipeline.
  */
class WordCache(
  val config: CoreConfig,
  val sets: Int,
  val sourceId: Int,
  val writable: Boolean,
) extends Module {
  require(sets == (1 << log2Ceil(sets)), "cache sets must be a power of two")

  val indexBits = log2Ceil(sets)
  val tagBits = 30 - indexBits

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new CacheRequest))
    val resp = Valid(new CacheResponse)
    val write = Input(new CacheWrite)

    val memRead = Decoupled(new MemoryReadRequest)
    val memResp = Flipped(Valid(new MemoryReadResponse))
  })

  private object State {
    val idle = 0.U(2.W)
    val memRequest = 1.U(2.W)
    val memWait = 2.U(2.W)
    val respond = 3.U(2.W)
  }

  val state = RegInit(State.idle)
  val valid = RegInit(VecInit(Seq.fill(sets)(false.B)))
  val tags = Reg(Vec(sets, UInt(tagBits.W)))
  val data = Reg(Vec(sets, UInt(32.W)))

  val requestWord = io.req.bits.addr(31, 2)
  val requestIndex = if (indexBits == 0) 0.U else requestWord(indexBits - 1, 0)
  val requestTag = requestWord >> indexBits
  val requestHit = valid(requestIndex) && tags(requestIndex) === requestTag

  val pendingIndex = Reg(UInt(math.max(indexBits, 1).W))
  val pendingTag = Reg(UInt(math.max(tagBits, 1).W))
  val pendingData = Reg(UInt(32.W))

  io.req.ready := state === State.idle
  io.resp.valid := false.B
  io.resp.bits.data := 0.U

  io.memRead.valid := state === State.memRequest
  io.memRead.bits.addr := Cat(pendingTag, pendingIndex, 0.U(2.W))
  io.memRead.bits.source := sourceId.U

  when(io.req.fire) {
    pendingIndex := requestIndex
    pendingTag := requestTag
    when(requestHit) {
      pendingData := data(requestIndex)
      state := State.respond
    }.otherwise {
      state := State.memRequest
    }
  }

  when(state === State.memRequest && io.memRead.ready) {
    state := State.memWait
  }

  when(state === State.memWait && io.memResp.valid && io.memResp.bits.source === sourceId.U) {
    valid(pendingIndex) := true.B
    tags(pendingIndex) := pendingTag
    data(pendingIndex) := io.memResp.bits.data
    io.resp.valid := true.B
    io.resp.bits.data := io.memResp.bits.data
    state := State.idle
  }

  when(state === State.respond) {
    io.resp.valid := true.B
    io.resp.bits.data := pendingData
    state := State.idle
  }

  if (writable) {
    val writeWord = io.write.addr(31, 2)
    val writeIndex = if (indexBits == 0) 0.U else writeWord(indexBits - 1, 0)
    val writeTag = writeWord >> indexBits
    when(io.write.valid && valid(writeIndex) && tags(writeIndex) === writeTag) {
      val bytes = data(writeIndex)
      val merged = Wire(Vec(4, UInt(8.W)))
      for (i <- 0 until 4) {
        merged(i) := Mux(io.write.strb(i), io.write.data(8 * i + 7, 8 * i), bytes(8 * i + 7, 8 * i))
      }
      data(writeIndex) := Cat(merged(3), merged(2), merged(1), merged(0))
    }
  }
}
