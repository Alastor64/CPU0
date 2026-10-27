package cpu

import chisel3._
import cpu.core.{CoreConfig, CpuCore}

/** Course-facing AXI4-Lite top level. */
class StudentTop(config: CoreConfig = CoreConfig()) extends Module with RequireSyncReset {
  override def desiredName = "student_top"

  val araddr = IO(Output(UInt(32.W)))
  val arvalid = IO(Output(Bool()))
  val arready = IO(Input(Bool()))

  val rdata = IO(Input(UInt(32.W)))
  val rresp = IO(Input(UInt(2.W)))
  val rvalid = IO(Input(Bool()))
  val rready = IO(Output(Bool()))

  val awaddr = IO(Output(UInt(32.W)))
  val awvalid = IO(Output(Bool()))
  val awready = IO(Input(Bool()))

  val wdata = IO(Output(UInt(32.W)))
  val wstrb = IO(Output(UInt(4.W)))
  val wvalid = IO(Output(Bool()))
  val wready = IO(Input(Bool()))

  val bresp = IO(Input(UInt(2.W)))
  val bvalid = IO(Input(Bool()))
  val bready = IO(Output(Bool()))

  val core = Module(new CpuCore(config))
  araddr := core.io.araddr
  arvalid := core.io.arvalid
  core.io.arready := arready
  rready := core.io.rready
  core.io.rdata := rdata
  core.io.rresp := rresp
  core.io.rvalid := rvalid
  awaddr := core.io.awaddr
  awvalid := core.io.awvalid
  core.io.awready := awready
  wdata := core.io.wdata
  wstrb := core.io.wstrb
  wvalid := core.io.wvalid
  core.io.wready := wready
  bready := core.io.bready
  core.io.bresp := bresp
  core.io.bvalid := bvalid
}
