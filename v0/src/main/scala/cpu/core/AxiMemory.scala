package cpu.core

import chisel3._
import chisel3.util._

class MemoryReadRequest extends Bundle {
  val addr = UInt(32.W)
  val source = UInt(2.W)
}

class MemoryReadResponse extends Bundle {
  val data = UInt(32.W)
  val source = UInt(2.W)
}

class MemoryWriteRequest extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val strb = UInt(4.W)
  val isExit = Bool()
}

class MemoryWriteResponse extends Bundle {
  val isExit = Bool()
}

/** Serialized AXI4-Lite master shared by the instruction cache, data cache and
  * store buffer. The course memory model permits one useful transaction at a
  * time for this first implementation, which keeps ordering explicit.
  */
class AxiMemory extends Module {
  val io = IO(new Bundle {
    val read = Flipped(Decoupled(new MemoryReadRequest))
    val readResp = Valid(new MemoryReadResponse)
    val write = Flipped(Decoupled(new MemoryWriteRequest))
    val writeResp = Valid(new MemoryWriteResponse)

    val araddr = Output(UInt(32.W))
    val arvalid = Output(Bool())
    val arready = Input(Bool())

    val rdata = Input(UInt(32.W))
    val rresp = Input(UInt(2.W))
    val rvalid = Input(Bool())
    val rready = Output(Bool())

    val awaddr = Output(UInt(32.W))
    val awvalid = Output(Bool())
    val awready = Input(Bool())

    val wdata = Output(UInt(32.W))
    val wstrb = Output(UInt(4.W))
    val wvalid = Output(Bool())
    val wready = Input(Bool())

    val bresp = Input(UInt(2.W))
    val bvalid = Input(Bool())
    val bready = Output(Bool())
  })

  private object State {
    val idle = 0.U(2.W)
    val readAddr = 1.U(2.W)
    val readData = 2.U(2.W)
    val write = 3.U(2.W)
  }

  val state = RegInit(State.idle)
  val readAddrReg = Reg(UInt(32.W))
  val readSourceReg = Reg(UInt(2.W))
  val writeReg = Reg(new MemoryWriteRequest)
  val awPending = RegInit(false.B)
  val wPending = RegInit(false.B)

  io.read.ready := state === State.idle && !io.write.valid
  io.write.ready := state === State.idle

  io.araddr := readAddrReg
  io.arvalid := state === State.readAddr
  io.rready := state === State.readData
  io.awaddr := writeReg.addr
  io.awvalid := state === State.write && awPending
  io.wdata := writeReg.data
  io.wstrb := writeReg.strb
  io.wvalid := state === State.write && wPending
  io.bready := state === State.write && !awPending && !wPending

  io.readResp.valid := false.B
  io.readResp.bits := 0.U.asTypeOf(new MemoryReadResponse)
  io.writeResp.valid := false.B
  io.writeResp.bits.isExit := false.B

  switch(state) {
    is(State.idle) {
      when(io.write.fire) {
        writeReg := io.write.bits
        awPending := true.B
        wPending := true.B
        state := State.write
      }.elsewhen(io.read.fire) {
        readAddrReg := io.read.bits.addr
        readSourceReg := io.read.bits.source
        state := State.readAddr
      }
    }

    is(State.readAddr) {
      when(io.arready) {
        state := State.readData
      }
    }

    is(State.readData) {
      when(io.rvalid) {
        io.readResp.valid := true.B
        io.readResp.bits.data := io.rdata
        io.readResp.bits.source := readSourceReg
        state := State.idle
      }
    }

    is(State.write) {
      when(io.awready) {
        awPending := false.B
      }
      when(io.wready) {
        wPending := false.B
      }
      when(!awPending && !wPending && io.bvalid) {
        io.writeResp.valid := true.B
        io.writeResp.bits.isExit := writeReg.isExit
        state := State.idle
      }
    }
  }
}
