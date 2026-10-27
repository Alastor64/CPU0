package cpu.core

import chisel3._
import chisel3.util._

/** Out-of-order execution core for RV32IM.
  *
  * Instructions are fetched and renamed in program order. The issue window
  * scans the ROB for ready instructions and can therefore execute around an
  * older instruction whose operands are not yet available. Results stay in the
  * ROB until in-order commit, at which point registers and stores become
  * architecturally visible.
  */
class CpuCore(val config: CoreConfig = CoreConfig()) extends Module {
  require(
    config.fetchQueueDepth == (1 << log2Ceil(config.fetchQueueDepth)),
    "fetchQueueDepth must currently be a power of two",
  )
  require(
    config.storeBufferDepth == (1 << log2Ceil(config.storeBufferDepth)),
    "storeBufferDepth must currently be a power of two",
  )
  require(
    config.robDepth == (1 << log2Ceil(config.robDepth)),
    "robDepth must currently be a power of two",
  )

  val io = IO(new Bundle {
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

    val halted = Output(Bool())
  })

  val memory = Module(new AxiMemory)
  val readArbiter = Module(new Arbiter(new MemoryReadRequest, 2))
  val iCache = Module(new InstructionCache(config, config.iCacheSets, sourceId = 0))
  val dCache = Module(
    new WordCache(config, config.dCacheSets, sourceId = 1, writable = true),
  )
  val execution = Module(new ExecutionUnit(config))
  val writeback = execution.io.writeback
  val writebackPc = Wire(UInt(32.W))
  val branchFlush = Wire(Bool())
  writebackPc := 0.U
  branchFlush := false.B

  dCache.io.write := 0.U.asTypeOf(new CacheWrite)
  execution.io.issue.valid := false.B
  execution.io.issue.bits := 0.U.asTypeOf(new ExecutionRequest)

  readArbiter.io.in(0) <> iCache.io.memRead
  readArbiter.io.in(1) <> dCache.io.memRead
  readArbiter.io.out <> memory.io.read
  iCache.io.memResp := memory.io.readResp
  dCache.io.memResp := memory.io.readResp

  io.araddr := memory.io.araddr
  io.arvalid := memory.io.arvalid
  memory.io.arready := io.arready
  memory.io.rdata := io.rdata
  memory.io.rresp := io.rresp
  memory.io.rvalid := io.rvalid
  io.rready := memory.io.rready
  io.awaddr := memory.io.awaddr
  io.awvalid := memory.io.awvalid
  memory.io.awready := io.awready
  io.wdata := memory.io.wdata
  io.wstrb := memory.io.wstrb
  io.wvalid := memory.io.wvalid
  memory.io.wready := io.wready
  memory.io.bresp := io.bresp
  memory.io.bvalid := io.bvalid
  io.bready := memory.io.bready

  val noTag = "hffffffff".U(32.W)

  def isStoreOp(op: OpType.Type): Bool =
    op === OpType.sb || op === OpType.sh || op === OpType.sw

  def isControlOp(op: OpType.Type): Bool =
    op === OpType.beq || op === OpType.bne || op === OpType.blt ||
      op === OpType.bge || op === OpType.bltu || op === OpType.bgeu ||
      op === OpType.jal || op === OpType.jalr

  def storeBytesFor(op: OpType.Type): UInt =
    Mux(op === OpType.sb, 1.U, Mux(op === OpType.sh, 2.U, 4.U))

  def controlWord(instruction: UInt): Bool = {
    val opcode = instruction(6, 0)
    opcode === "b1100011".U || opcode === "b1100111".U || opcode === "b1101111".U
  }

  val fetchQueueValid = RegInit(VecInit(Seq.fill(config.fetchQueueDepth)(false.B)))
  val fetchQueuePc = Reg(Vec(config.fetchQueueDepth, UInt(32.W)))
  val fetchQueueInstruction = Reg(Vec(config.fetchQueueDepth, UInt(32.W)))
  val fetchReadPtr = RegInit(0.U(config.fetchQueueIndexBits.W))
  val fetchWritePtr = RegInit(0.U(config.fetchQueueIndexBits.W))
  val fetchCount = RegInit(0.U((config.fetchQueueIndexBits + 1).W))
  val fetchPc = RegInit(0.U(32.W))
  val fetchPending = RegInit(false.B)
  val fetchBarrier = RegInit(false.B)
  val halted = RegInit(false.B)

  io.halted := halted

  iCache.io.req.valid :=
    !fetchPending && !fetchBarrier && !halted &&
      (fetchCount < config.fetchQueueDepth.U)
  iCache.io.req.bits.addr := fetchPc

  val fetchResponse = iCache.io.resp
  val fetchEnqueueCount = Wire(UInt(3.W))
  val fetchEnqueue = Wire(Bool())
  val fetchedControl = Wire(Bool())
  val fetchPrefixStop = Wire(Vec(5, Bool()))
  val fetchWordCanEnqueue = Wire(Vec(4, Bool()))
  fetchedControl := false.B
  fetchPrefixStop(0) := false.B

  when(iCache.io.req.fire) {
    fetchPending := true.B
  }

  val fetchRoom = config.fetchQueueDepth.U - fetchCount
  for (i <- 0 until 4) {
    val wordIndex = Cat(0.U(1.W), fetchResponse.bits.startOffset) + i.U(3.W)
    val withinLine = wordIndex < 4.U
    val safeWordIndex = Mux(withinLine, wordIndex, 0.U)
    val instruction = fetchResponse.bits.words(safeWordIndex)
    val hasRoom = withinLine && i.U < fetchRoom
    val canEnqueue = fetchResponse.valid && hasRoom && !fetchPrefixStop(i)
    val queueIndex = (fetchWritePtr + i.U) % config.fetchQueueDepth.U
    val instructionPc = fetchPc + (i.U << 2)
    fetchWordCanEnqueue(i) := canEnqueue
    fetchPrefixStop(i + 1) := fetchPrefixStop(i) || (hasRoom && controlWord(instruction))
    when(canEnqueue) {
      fetchQueueValid(queueIndex) := true.B
      fetchQueuePc(queueIndex) := instructionPc
      fetchQueueInstruction(queueIndex) := instruction
    }
  }
  fetchEnqueue := fetchWordCanEnqueue.asUInt.orR
  fetchEnqueueCount := PopCount(fetchWordCanEnqueue)
  fetchedControl := VecInit((0 until 4).map { i =>
    fetchWordCanEnqueue(i) && controlWord(fetchResponse.bits.words(
      Mux(fetchResponse.bits.startOffset + i.U < 4.U, fetchResponse.bits.startOffset + i.U, 0.U),
    ))
  }).asUInt.orR

  when(fetchEnqueue) {
    fetchWritePtr := (fetchWritePtr + fetchEnqueueCount) % config.fetchQueueDepth.U
    fetchPc := fetchPc + (fetchEnqueueCount << 2)
    fetchPending := false.B
    when(fetchedControl) {
      fetchBarrier := true.B
    }
  }

  val decoded = Seq.fill(config.issueWidth)(Module(new InstructionDecoder))
  val dispatchValid = Wire(Vec(config.issueWidth, Bool()))
  val dispatchStop = Wire(Vec(config.issueWidth + 1, Bool()))

  val rob = Reg(Vec(config.robDepth, new CoreRobEntry))
  val robHead = RegInit(0.U(config.robIndexBits.W))
  val robTail = RegInit(0.U(config.robIndexBits.W))
  val robCount = RegInit(0.U((config.robIndexBits + 1).W))
  val nextTag = RegInit(1.U(32.W))
  val regValues = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  val producerTags = RegInit(VecInit(Seq.fill(32)(noTag)))

  for (i <- 0 until config.issueWidth) {
    val entryIndex = (fetchReadPtr + i.U) % config.fetchQueueDepth.U
    val hasInstruction = i.U < fetchCount
    decoded(i).io.valid := hasInstruction && fetchQueueValid(entryIndex)
    decoded(i).io.pc := fetchQueuePc(entryIndex)
    decoded(i).io.instruction := fetchQueueInstruction(entryIndex)
  }

  val robFree = config.robDepth.U - robCount
  val dispatchCount = Wire(UInt((config.robIndexBits + 1).W))
  dispatchStop(0) := halted || branchFlush || robFree === 0.U

  for (i <- 0 until config.issueWidth) {
    val canDispatch =
      !dispatchStop(i) && i.U < fetchCount && i.U < robFree &&
        decoded(i).io.decoded.valid
    dispatchValid(i) := canDispatch
    dispatchStop(i + 1) := dispatchStop(i)
  }
  dispatchCount := PopCount(dispatchValid)

  for (i <- 0 until config.issueWidth) {
    val decodedEntry = decoded(i).io.decoded
    val slot = (robTail + i.U) % config.robDepth.U
    val destinationTag = nextTag + i.U

    val source1Tag = Wire(UInt(32.W))
    val source1Ready = Wire(Bool())
    val source1Value = Wire(UInt(32.W))
    val source2Tag = Wire(UInt(32.W))
    val source2Ready = Wire(Bool())
    val source2Value = Wire(UInt(32.W))

    source1Tag := Mux(decodedEntry.usesRs1, producerTags(decodedEntry.rs1), noTag)
    source2Tag := Mux(decodedEntry.usesRs2, producerTags(decodedEntry.rs2), noTag)
    for (prior <- 0 until i) {
      when(
        dispatchValid(prior) && decoded(prior).io.decoded.writesRd &&
          decoded(prior).io.decoded.rd === decodedEntry.rs1,
      ) {
        source1Tag := nextTag + prior.U
      }
      when(
        dispatchValid(prior) && decoded(prior).io.decoded.writesRd &&
          decoded(prior).io.decoded.rd === decodedEntry.rs2,
      ) {
        source2Tag := nextTag + prior.U
      }
    }
    source1Ready := !decodedEntry.usesRs1 || source1Tag === noTag
    source2Ready := !decodedEntry.usesRs2 || source2Tag === noTag
    source1Value := regValues(decodedEntry.rs1)
    source2Value := regValues(decodedEntry.rs2)

    for (j <- 0 until config.robDepth) {
      when(decodedEntry.usesRs1 && rob(j).valid && rob(j).tag === source1Tag) {
        source1Ready := rob(j).ready
        source1Value := rob(j).value
      }
      when(decodedEntry.usesRs2 && rob(j).valid && rob(j).tag === source2Tag) {
        source2Ready := rob(j).ready
        source2Value := rob(j).value
      }
    }

    val writesDestination = decodedEntry.writesRd && decodedEntry.rd =/= 0.U
    when(dispatchValid(i)) {
      rob(slot).valid := true.B
      rob(slot).ready := false.B
      rob(slot).issued := false.B
      rob(slot).tag := destinationTag
      rob(slot).pc := decodedEntry.pc
      rob(slot).op := decodedEntry.op
      rob(slot).rd := decodedEntry.rd
      rob(slot).imm := decodedEntry.imm
      rob(slot).writesRd := writesDestination
      rob(slot).rs1Ready := source1Ready
      rob(slot).rs1Tag := source1Tag
      rob(slot).rs1Value := source1Value
      rob(slot).rs2Ready := source2Ready
      rob(slot).rs2Tag := source2Tag
      rob(slot).rs2Value := source2Value
      rob(slot).value := 0.U
      rob(slot).isStore := isStoreOp(decodedEntry.op)
      rob(slot).storeBytes := storeBytesFor(decodedEntry.op)
      rob(slot).storeAddr := 0.U
      rob(slot).storeData := 0.U
      rob(slot).isControl := isControlOp(decodedEntry.op)
      rob(slot).branchTaken := false.B
      rob(slot).branchTarget := 0.U

    }
  }

  when(dispatchCount > 0.U) {
    robTail := (robTail + dispatchCount) % config.robDepth.U
    nextTag := nextTag + dispatchCount
    fetchReadPtr := (fetchReadPtr + dispatchCount) % config.fetchQueueDepth.U
  }

  when(fetchEnqueue || dispatchCount > 0.U) {
    fetchCount := fetchCount + fetchEnqueueCount - dispatchCount
  }

  val storeValid = RegInit(VecInit(Seq.fill(config.storeBufferDepth)(false.B)))
  val storeAddr = Reg(Vec(config.storeBufferDepth, UInt(32.W)))
  val storeData = Reg(Vec(config.storeBufferDepth, UInt(32.W)))
  val storeStrb = Reg(Vec(config.storeBufferDepth, UInt(4.W)))
  val storeIsExit = Reg(Vec(config.storeBufferDepth, Bool()))
  val storeHead = RegInit(0.U(config.storeBufferIndexBits.W))
  val storeTail = RegInit(0.U(config.storeBufferIndexBits.W))
  val storeCount = RegInit(0.U((config.storeBufferIndexBits + 1).W))
  val storeEnqueue = Wire(Bool())
  val storeDequeue = memory.io.writeResp.valid

  memory.io.write.valid := storeCount =/= 0.U
  memory.io.write.bits.addr := storeAddr(storeHead)
  memory.io.write.bits.data := storeData(storeHead)
  memory.io.write.bits.strb := storeStrb(storeHead)
  memory.io.write.bits.isExit := storeIsExit(storeHead)

  val issueCandidate = Wire(Vec(config.robDepth, Bool()))
  val candidateRs1Value = Wire(Vec(config.robDepth, UInt(32.W)))
  val candidateRs2Value = Wire(Vec(config.robDepth, UInt(32.W)))
  val issueFound = Wire(Bool())
  val issueSlot = Wire(UInt(config.robIndexBits.W))

  val functionalUnitAvailable =
    execution.io.issue.ready && !halted

  for (age <- 0 until config.robDepth) {
    val slot = (robHead + age.U) % config.robDepth.U
    val entry = rob(slot)
    val source1Ready = Wire(Bool())
    val source2Ready = Wire(Bool())
    val source1Value = Wire(UInt(32.W))
    val source2Value = Wire(UInt(32.W))
    var olderStore = false.B
    source1Ready := entry.rs1Ready
    source2Ready := entry.rs2Ready
    source1Value := entry.rs1Value
    source2Value := entry.rs2Value
    for (j <- 0 until config.robDepth) {
      when(rob(j).valid && rob(j).ready && rob(j).tag === entry.rs1Tag) {
        source1Ready := true.B
        source1Value := rob(j).value
      }
      when(rob(j).valid && rob(j).ready && rob(j).tag === entry.rs2Tag) {
        source2Ready := true.B
        source2Value := rob(j).value
      }
    }
    for (older <- 0 until age) {
      val olderSlot = (robHead + older.U) % config.robDepth.U
      olderStore = olderStore || (rob(olderSlot).valid && rob(olderSlot).isStore)
    }
    val isLoad = entry.op === OpType.lb || entry.op === OpType.lbu ||
      entry.op === OpType.lh || entry.op === OpType.lhu || entry.op === OpType.lw
    val memoryOrdered = !isLoad || (!olderStore && storeCount === 0.U)
    issueCandidate(age) := entry.valid && !entry.ready && !entry.issued &&
      source1Ready && source2Ready && memoryOrdered && functionalUnitAvailable
    candidateRs1Value(age) := source1Value
    candidateRs2Value(age) := source2Value
  }

  val selectedIssue = PriorityEncoderOH(issueCandidate)
  val issueTags = VecInit((0 until config.robDepth).map { age =>
    rob((robHead + age.U) % config.robDepth.U).tag
  })
  val issuePcs = VecInit((0 until config.robDepth).map { age =>
    rob((robHead + age.U) % config.robDepth.U).pc
  })
  val issueOps = VecInit((0 until config.robDepth).map { age =>
    rob((robHead + age.U) % config.robDepth.U).op
  })
  val issueRd = VecInit((0 until config.robDepth).map { age =>
    rob((robHead + age.U) % config.robDepth.U).rd
  })
  val issueImm = VecInit((0 until config.robDepth).map { age =>
    rob((robHead + age.U) % config.robDepth.U).imm
  })
  val issueIsStore = VecInit((0 until config.robDepth).map { age =>
    rob((robHead + age.U) % config.robDepth.U).isStore
  })
  val issueStoreBytes = VecInit((0 until config.robDepth).map { age =>
    rob((robHead + age.U) % config.robDepth.U).storeBytes
  })

  issueFound := issueCandidate.asUInt.orR
  issueSlot := (robHead + OHToUInt(selectedIssue)) % config.robDepth.U
  execution.io.issue.valid := issueFound
  execution.io.issue.bits.tag := Mux1H(selectedIssue, issueTags)
  execution.io.issue.bits.pc := Mux1H(selectedIssue, issuePcs)
  execution.io.issue.bits.op := Mux1H(selectedIssue, issueOps)
  execution.io.issue.bits.rd := Mux1H(selectedIssue, issueRd)
  execution.io.issue.bits.imm := Mux1H(selectedIssue, issueImm)
  execution.io.issue.bits.rs1 := Mux1H(selectedIssue, candidateRs1Value)
  execution.io.issue.bits.rs2 := Mux1H(selectedIssue, candidateRs2Value)
  execution.io.issue.bits.isStore := Mux1H(selectedIssue, issueIsStore)
  execution.io.issue.bits.storeBytes := Mux1H(selectedIssue, issueStoreBytes)

  when(issueFound && execution.io.issue.fire) {
    rob(issueSlot).issued := true.B
    rob(issueSlot).rs1Value := execution.io.issue.bits.rs1
    rob(issueSlot).rs2Value := execution.io.issue.bits.rs2
  }

  val branchSlot = Wire(UInt(config.robIndexBits.W))
  val branchSlotValid = Wire(Bool())
  val flushKeepCount = Wire(UInt((config.robIndexBits + 1).W))
  branchSlot := 0.U
  branchSlotValid := false.B
  flushKeepCount := 0.U
  for (i <- 0 until config.robDepth) {
    when(rob(i).valid && rob(i).tag === writeback.bits.tag) {
      branchSlot := i.U
      branchSlotValid := true.B
      rob(i).ready := true.B
      rob(i).issued := true.B
      rob(i).value := writeback.bits.value
      rob(i).storeAddr := writeback.bits.storeAddr
      rob(i).storeData := writeback.bits.storeData
      rob(i).branchTaken := writeback.bits.branchTaken
      rob(i).branchTarget := writeback.bits.branchTarget
      writebackPc := rob(i).pc
    }
  }

  branchFlush := writeback.valid && writeback.bits.isControl && writeback.bits.branchTaken

  when(writeback.valid && writeback.bits.isControl) {
    when(branchFlush) {
      val keepMask = Wire(Vec(config.robDepth, Bool()))
      val restoredTags = Wire(Vec(32, UInt(32.W)))
      for (i <- 0 until 32) {
        restoredTags(i) := noTag
      }
      for (i <- 0 until config.robDepth) {
        val slot = (robHead + i.U) % config.robDepth.U
        keepMask(i) := rob(slot).valid && rob(slot).tag <= writeback.bits.tag
        when(keepMask(i) && rob(slot).writesRd) {
          restoredTags(rob(slot).rd) := rob(slot).tag
        }
        when(!keepMask(i)) {
          rob(slot).valid := false.B
        }
      }
      flushKeepCount := PopCount(keepMask)
      robTail := (branchSlot + 1.U) % config.robDepth.U
      fetchPc := Mux(writeback.bits.branchTaken, writeback.bits.branchTarget, writebackPc + 4.U)
      fetchReadPtr := fetchWritePtr
      fetchCount := 0.U
      for (i <- 0 until config.fetchQueueDepth) {
        fetchQueueValid(i) := false.B
      }
      fetchPending := false.B
      fetchBarrier := false.B
      for (i <- 0 until 32) {
        producerTags(i) := restoredTags(i)
      }
    }.otherwise {
      fetchBarrier := false.B
    }
  }

  val commitEntry = rob(robHead)
  val commitIsExit = commitEntry.isStore &&
    commitEntry.storeAddr === "h80000000".U && commitEntry.storeBytes === 4.U
  val alignedStoreAddr = commitEntry.storeAddr & "hfffffffc".U
  val storeLaneShift = Cat(commitEntry.storeAddr(1, 0), 0.U(3.W))
  val alignedStoreData = commitEntry.storeData << storeLaneShift
  val storeBaseMask = MuxLookup(commitEntry.storeBytes, "hf".U)(
    Seq(1.U -> "h1".U, 2.U -> "h3".U, 4.U -> "hf".U),
  )
  val alignedStoreStrobe = storeBaseMask << commitEntry.storeAddr(1, 0)
  val canCommit =
    commitEntry.valid && commitEntry.ready && !halted && !branchFlush &&
      (!commitEntry.isStore || storeCount < config.storeBufferDepth.U) &&
      !(writeback.valid && writeback.bits.branchTaken && commitEntry.tag === writeback.bits.tag)

  storeEnqueue := false.B
  when(canCommit) {
    when(commitEntry.isStore) {
      storeValid(storeTail) := true.B
      storeAddr(storeTail) := alignedStoreAddr
      storeData(storeTail) := alignedStoreData
      storeStrb(storeTail) := alignedStoreStrobe
      storeIsExit(storeTail) := commitIsExit
      storeTail := (storeTail + 1.U) % config.storeBufferDepth.U
      storeEnqueue := true.B
      dCache.io.write.valid := !commitIsExit
      dCache.io.write.addr := alignedStoreAddr
      dCache.io.write.data := alignedStoreData
      dCache.io.write.strb := alignedStoreStrobe
    }.otherwise {
      dCache.io.write.valid := false.B
      when(commitEntry.writesRd) {
        regValues(commitEntry.rd) := commitEntry.value
        when(producerTags(commitEntry.rd) === commitEntry.tag) {
          producerTags(commitEntry.rd) := noTag
        }
        for (i <- 0 until config.robDepth) {
          when(
            rob(i).valid && !rob(i).ready && rob(i).rs1Tag === commitEntry.tag,
          ) {
            rob(i).rs1Ready := true.B
            rob(i).rs1Tag := noTag
            rob(i).rs1Value := commitEntry.value
          }
          when(
            rob(i).valid && !rob(i).ready && rob(i).rs2Tag === commitEntry.tag,
          ) {
            rob(i).rs2Ready := true.B
            rob(i).rs2Tag := noTag
            rob(i).rs2Value := commitEntry.value
          }
        }
      }
    }
    rob(robHead).valid := false.B
    robHead := (robHead + 1.U) % config.robDepth.U
    when(commitIsExit) {
      halted := true.B
      fetchCount := 0.U
      fetchReadPtr := fetchWritePtr
      fetchBarrier := true.B
      fetchPending := false.B
      for (i <- 0 until config.robDepth) {
        rob(i).valid := false.B
      }
    }
  }.otherwise {
    dCache.io.write.valid := false.B
  }

  val storesAfterWrite = storeCount + storeEnqueue.asUInt - storeDequeue.asUInt
  when(storeEnqueue || storeDequeue) {
    storeCount := storesAfterWrite
  }
  when(storeDequeue) {
    storeValid(storeHead) := false.B
    storeHead := (storeHead + 1.U) % config.storeBufferDepth.U
  }

  when(branchFlush) {
    robCount := flushKeepCount
  }.elsewhen(canCommit) {
    robCount := Mux(commitIsExit, 0.U, robCount + dispatchCount - 1.U)
  }.elsewhen(dispatchCount > 0.U) {
    robCount := robCount + dispatchCount
  }

  for (i <- 0 until config.issueWidth) {
    val decodedEntry = decoded(i).io.decoded
    when(dispatchValid(i) && decodedEntry.writesRd && decodedEntry.rd =/= 0.U) {
      producerTags(decodedEntry.rd) := nextTag + i.U
    }
  }

  dCache.io.req <> execution.io.cacheReq
  execution.io.cacheResp := dCache.io.resp
}
