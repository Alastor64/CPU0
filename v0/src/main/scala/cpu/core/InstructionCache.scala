package cpu.core

import chisel3._
import chisel3.util._

class InstructionLineResponse extends Bundle {
  val words = Vec(4, UInt(32.W))
  val startOffset = UInt(2.W)
}

/** Four-word instruction cache.
  *
  * A miss fills all four AXI words before publishing the line. Hits return the
  * complete line so the frontend can enqueue up to four consecutive
  * instructions in one cycle.
  */
class InstructionCache(val config: CoreConfig, val sets: Int, val sourceId: Int) extends Module {
  require(sets == (1 << log2Ceil(sets)), "cache sets must be a power of two")

  val indexBits = log2Ceil(sets)
  val tagBits = 28 - indexBits

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new CacheRequest))
    val resp = Valid(new InstructionLineResponse)

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
  val lines = Reg(Vec(sets, Vec(4, UInt(32.W))))

  val requestLine = io.req.bits.addr(31, 4)
  val requestIndex = if (indexBits == 0) 0.U else requestLine(indexBits - 1, 0)
  val requestTag = requestLine >> indexBits
  val requestOffset = io.req.bits.addr(3, 2)
  val requestHit = valid(requestIndex) && tags(requestIndex) === requestTag

  val pendingIndex = Reg(UInt(math.max(indexBits, 1).W))
  val pendingTag = Reg(UInt(math.max(tagBits, 1).W))
  val pendingBase = Reg(UInt(32.W))
  val pendingOffset = Reg(UInt(2.W))
  val fillWord = RegInit(0.U(2.W))
  val fillData = Reg(Vec(4, UInt(32.W)))
  val replyData = Reg(Vec(4, UInt(32.W)))
  val replyOffset = Reg(UInt(2.W))

  io.req.ready := state === State.idle
  io.resp.valid := state === State.respond
  io.resp.bits.words := replyData
  io.resp.bits.startOffset := replyOffset

  io.memRead.valid := state === State.memRequest
  io.memRead.bits.addr := pendingBase + (fillWord << 2)
  io.memRead.bits.source := sourceId.U

  when(io.req.fire) {
    pendingIndex := requestIndex
    pendingTag := requestTag
    pendingBase := io.req.bits.addr & "hfffffff0".U
    pendingOffset := requestOffset
    replyOffset := requestOffset
    when(requestHit) {
      replyData := lines(requestIndex)
      state := State.respond
    }.otherwise {
      fillWord := 0.U
      state := State.memRequest
    }
  }

  when(state === State.memRequest && io.memRead.ready) {
    state := State.memWait
  }

  when(state === State.memWait && io.memResp.valid && io.memResp.bits.source === sourceId.U) {
    fillData(fillWord) := io.memResp.bits.data
    when(fillWord === 3.U) {
      valid(pendingIndex) := true.B
      tags(pendingIndex) := pendingTag
      lines(pendingIndex) := fillData
      lines(pendingIndex)(3) := io.memResp.bits.data
      replyData := fillData
      replyData(3) := io.memResp.bits.data
      state := State.respond
    }.otherwise {
      fillWord := fillWord + 1.U
      state := State.memRequest
    }
  }

  when(state === State.respond) {
    state := State.idle
  }
}
