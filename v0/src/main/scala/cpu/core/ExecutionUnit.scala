package cpu.core

import chisel3._
import chisel3.util._

class ExecutionRequest extends Bundle {
  val tag = UInt(32.W)
  val pc = UInt(32.W)
  val op = OpType()
  val rd = UInt(5.W)
  val imm = SInt(32.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val isStore = Bool()
  val storeBytes = UInt(3.W)
}

class ExecutionWriteback extends Bundle {
  val tag = UInt(32.W)
  val value = UInt(32.W)
  val storeAddr = UInt(32.W)
  val storeData = UInt(32.W)
  val isStore = Bool()
  val isControl = Bool()
  val branchTaken = Bool()
  val branchTarget = UInt(32.W)
}

/** One integer execution pipe.
  *
  * The pipe executes ALU, multiply/divide, branch and address generation in
  * one cycle. Loads use the data cache request interface and may take multiple
  * cycles. Stores only produce an address and data; they are sent to memory at
  * commit time, preserving architectural order.
  */
class ExecutionUnit(val config: CoreConfig) extends Module {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new ExecutionRequest))
    val writeback = Valid(new ExecutionWriteback)
    val cacheReq = Decoupled(new CacheRequest)
    val cacheResp = Flipped(Valid(new CacheResponse))
  })

  private object State {
    val idle = 0.U(2.W)
    val execute = 1.U(2.W)
    val cacheRequest = 2.U(2.W)
    val cacheWait = 3.U(2.W)
  }

  val state = RegInit(State.idle)
  val request = Reg(new ExecutionRequest)
  val loadAddr = Reg(UInt(32.W))

  io.issue.ready := state === State.idle
  io.writeback.valid := false.B
  io.writeback.bits := 0.U.asTypeOf(new ExecutionWriteback)
  io.cacheReq.valid := state === State.cacheRequest
  io.cacheReq.bits.addr := loadAddr

  def isLoad(op: OpType.Type): Bool =
    op === OpType.lb || op === OpType.lbu || op === OpType.lh ||
      op === OpType.lhu || op === OpType.lw

  def isBranch(op: OpType.Type): Bool =
    op === OpType.beq || op === OpType.bne || op === OpType.blt ||
      op === OpType.bge || op === OpType.bltu || op === OpType.bgeu

  def isControl(op: OpType.Type): Bool =
    isBranch(op) || op === OpType.jal || op === OpType.jalr

  def aluResult(req: ExecutionRequest): UInt = {
    val a = req.rs1
    val b = req.rs2
    val imm = req.imm.asUInt
    val shamt = b(4, 0)
    val immediateShamt = imm(4, 0)
    val product = a * b
    val signedProduct = a.asSInt * b.asSInt
    val unsignedProduct = a.pad(64) * b.pad(64)
    val b64Positive = b.pad(64).asSInt
    val mulhsuProduct = a.asSInt.pad(64) * b64Positive
    val divByZero = b === 0.U
    val signedOverflow = a === "h80000000".U && b === "hffffffff".U
    val signedQuotient = a.asSInt / b.asSInt
    val signedRemainder = a.asSInt % b.asSInt
    val unsignedQuotient = a / b
    val unsignedRemainder = a % b

    MuxLookup(req.op, 0.U)(
      Seq(
        OpType.add -> (a + b),
        OpType.sub -> (a - b),
        OpType.sll -> (a << shamt),
        OpType.slt -> Mux(a.asSInt < b.asSInt, 1.U, 0.U),
        OpType.sltu -> Mux(a < b, 1.U, 0.U),
        OpType.xor -> (a ^ b),
        OpType.srl -> (a >> shamt),
        OpType.sra -> (a.asSInt >> shamt).asUInt,
        OpType.or -> (a | b),
        OpType.and -> (a & b),
        OpType.addi -> (a + imm),
        OpType.slti -> Mux(a.asSInt < req.imm, 1.U, 0.U),
        OpType.sltiu -> Mux(a < imm, 1.U, 0.U),
        OpType.xori -> (a ^ imm),
        OpType.ori -> (a | imm),
        OpType.andi -> (a & imm),
        OpType.slli -> (a << immediateShamt),
        OpType.srli -> (a >> immediateShamt),
        OpType.srai -> (a.asSInt >> immediateShamt).asUInt,
        OpType.lui -> imm,
        OpType.auipc -> (req.pc + imm),
        OpType.jal -> (req.pc + 4.U),
        OpType.jalr -> (req.pc + 4.U),
        OpType.mul -> product(31, 0),
        OpType.mulh -> signedProduct(63, 32).asUInt,
        OpType.mulhsu -> mulhsuProduct(63, 32).asUInt,
        OpType.mulhu -> unsignedProduct(63, 32),
        OpType.div -> Mux(
          divByZero,
          "hffffffff".U,
          Mux(signedOverflow, "h80000000".U, signedQuotient.asUInt),
        ),
        OpType.divu -> Mux(divByZero, "hffffffff".U, unsignedQuotient),
        OpType.rem -> Mux(
          divByZero,
          a,
          Mux(signedOverflow, 0.U, signedRemainder.asUInt),
        ),
        OpType.remu -> Mux(divByZero, a, unsignedRemainder),
      ),
    )
  }

  def branchTaken(req: ExecutionRequest): Bool =
    MuxLookup(req.op, false.B)(
      Seq(
        OpType.beq -> (req.rs1 === req.rs2),
        OpType.bne -> (req.rs1 =/= req.rs2),
        OpType.blt -> (req.rs1.asSInt < req.rs2.asSInt),
        OpType.bge -> (req.rs1.asSInt >= req.rs2.asSInt),
        OpType.bltu -> (req.rs1 < req.rs2),
        OpType.bgeu -> (req.rs1 >= req.rs2),
        OpType.jal -> true.B,
        OpType.jalr -> true.B,
      ),
    )

  def controlTarget(req: ExecutionRequest): UInt =
    Mux(
      req.op === OpType.jalr,
      (req.rs1 + req.imm.asUInt) & "hfffffffe".U,
      req.pc + req.imm.asUInt,
    )

  def loadValue(op: OpType.Type, address: UInt, word: UInt): UInt = {
    val shift = Cat(address(1, 0), 0.U(3.W))
    val shifted = word >> shift
    val byte = shifted(7, 0)
    val half = shifted(15, 0)
    MuxLookup(op, shifted)(
      Seq(
        OpType.lb -> Cat(Fill(24, byte(7)), byte),
        OpType.lbu -> byte,
        OpType.lh -> Cat(Fill(16, half(15)), half),
        OpType.lhu -> half,
        OpType.lw -> word,
      ),
    )
  }

  when(io.issue.fire) {
    request := io.issue.bits
    loadAddr := io.issue.bits.rs1 + io.issue.bits.imm.asUInt
    state := Mux(isLoad(io.issue.bits.op), State.cacheRequest, State.execute)
  }

  when(state === State.execute) {
    val req = request
    val isStoreReq = req.isStore
    val isControlReq = isControl(req.op)
    val taken = branchTaken(req)
    io.writeback.valid := true.B
    io.writeback.bits.tag := req.tag
    io.writeback.bits.value := aluResult(req)
    io.writeback.bits.storeAddr := req.rs1 + req.imm.asUInt
    io.writeback.bits.storeData := req.rs2
    io.writeback.bits.isStore := isStoreReq
    io.writeback.bits.isControl := isControlReq
    io.writeback.bits.branchTaken := taken
    io.writeback.bits.branchTarget := controlTarget(req)
    state := State.idle
  }

  when(state === State.cacheRequest && io.cacheReq.ready) {
    state := State.cacheWait
  }

  when(state === State.cacheWait && io.cacheResp.valid) {
    io.writeback.valid := true.B
    io.writeback.bits.tag := request.tag
    io.writeback.bits.value := loadValue(request.op, loadAddr, io.cacheResp.bits.data)
    io.writeback.bits.isStore := false.B
    io.writeback.bits.isControl := false.B
    io.writeback.bits.branchTaken := false.B
    io.writeback.bits.branchTarget := 0.U
    io.writeback.bits.storeAddr := 0.U
    io.writeback.bits.storeData := 0.U
    state := State.idle
  }
}
