//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Predictor

import Config._
import Config.CPUConfig._
import chisel3._
import chisel3.util._
import cpu2026.SRAM

/** One PHT training request. The counter value itself lives in SRAM. */
class PHTUpdate extends Bundle {
  val subaddr = UInt(PBIT.W)
  val localHistory = UInt(KBIT.W)
  val actualTaken = Bool()
}

/**
  * Branch predictor with the large PHT moved to four single-port SRAM banks.
  *
  * The PHT is organized as:
  *   bank                    = subaddr[1:0]
  *   bank-local PHT address = {subaddr[7:2], localHistory}
  *
  * Four consecutive fetch candidates therefore access four distinct banks.
  * A PHT update is a read-modify-write and occupies one bank for two cycles:
  * one cycle to read the old counter and one cycle to write the new counter.
  */
class Predictor extends Module {
  val io = IO(new Bundle {
    val qaddrs = Input(Vec(issueWidth, UInt(32.W)))
    /** Query a complete candidate group. The result appears one cycle later. */
    val queryValid = Input(Bool())
    val uvalid = Input(Bool())
    val uaddr = Input(UInt(32.W))
    val ures = Input(UInt(32.W))

    /** Asserted while a pending training request blocks PHT reads. */
    val stall = Output(Bool())
    val out = Output(Vec(issueWidth, UInt(32.W)))
    val outPc = Output(UInt(32.W))
    val outValid = Output(Bool())
  })

  private val bankCount = issueWidth
  private val bankSelectBits = log2Ceil(bankCount)
  private val bankDepth = KSIZE / bankCount
  private val bankAddressBits = log2Ceil(bankDepth)
  require((1 << bankSelectBits) == bankCount,
    "PHT banking requires a power-of-two issue width")
  require((1 << bankAddressBits) == bankDepth,
    "PHT bank depth must be a power of two")

  private val sIdle = 0.U(1.W)
  private val sUpdateRead = 1.U(1.W)
  private val queueDepth = ROBWidth
  private val queueCountBits = log2Ceil(queueDepth + 1)
  private val queueIndexBits = log2Ceil(queueDepth)

  // Small predictor tables remain in registers. Keeping LHT here also preserves
  // the single-cycle {PC, localHistory} dependency before the PHT SRAM read.
  val LHT = RegInit(VecInit(Seq.fill(PSIZE)(0.U(KBIT.W))))
  val Gshare_status = RegInit(VecInit(Seq.fill(PSIZE)(1.U(3.W))))
  val score = RegInit(VecInit(Seq.fill(PSIZE)(1.U(3.W))))
  val history = RegInit(0.U(PBIT.W))

  // SRAM has no reset value. A parallel validity bitmap makes unseen entries
  // behave exactly like the previous RegInit(1) PHT.
  val phtValid = RegInit(VecInit(Seq.fill(KSIZE)(false.B)))

  val phtBanks = Seq.tabulate(bankCount) { _ =>
    Module(new SRAM(bankDepth, 3, 3))
  }
  for (bank <- phtBanks) {
    bank.clk := clock
    bank.en := false.B
    bank.we := false.B
    bank.wmask := 0.U
    bank.addr := 0.U
    bank.wdata := 0.U
  }

  val querySubaddr = Wire(Vec(issueWidth, UInt(PBIT.W)))
  val queryLocalHistory = Wire(Vec(issueWidth, UInt(KBIT.W)))
  val queryBankAddress = Wire(Vec(issueWidth, UInt(bankAddressBits.W)))
  val queryGshare = Wire(Vec(issueWidth, Bool()))
  val queryUseGshare = Wire(Vec(issueWidth, Bool()))
  val queryPhtValid = Wire(Vec(issueWidth, Bool()))

  for (i <- 0 until issueWidth) {
    querySubaddr(i) := io.qaddrs(i)(PBIT + 1, 2)
    queryLocalHistory(i) := LHT(querySubaddr(i))
    queryBankAddress(i) := Cat(
      querySubaddr(i)(PBIT - 1, bankSelectBits),
      queryLocalHistory(i),
    )
    queryGshare(i) := Gshare_status(querySubaddr(i) ^ history) >= 2.U
    queryUseGshare(i) := score(querySubaddr(i)) >= 2.U
    queryPhtValid(i) := phtValid(Cat(querySubaddr(i), queryLocalHistory(i)))
  }

  val queryEnabled = io.queryValid && !io.stall
  when(queryEnabled) {
    for (bank <- 0 until bankCount) {
      for (i <- 0 until issueWidth) {
        when(querySubaddr(i)(bankSelectBits - 1, 0) === bank.U) {
          phtBanks(bank).en := true.B
          phtBanks(bank).we := false.B
          phtBanks(bank).addr := queryBankAddress(i)
        }
      }
    }
  }

  // Capture all non-SRAM query state. The SRAM read result becomes valid on the
  // next edge, at which point queryFresh is high.
  val queryFresh = RegInit(false.B)
  val queryPc = RegInit(0.U(32.W))
  val queryBankSelectReg =
    RegInit(VecInit(Seq.fill(issueWidth)(0.U(bankSelectBits.W))))
  val queryLocalHistoryReg =
    RegInit(VecInit(Seq.fill(issueWidth)(0.U(KBIT.W))))
  val queryHistoryReg = RegInit(0.U(PBIT.W))
  val queryGshareReg =
    RegInit(VecInit(Seq.fill(issueWidth)(false.B)))
  val queryUseGshareReg =
    RegInit(VecInit(Seq.fill(issueWidth)(false.B)))
  val queryPhtValidReg =
    RegInit(VecInit(Seq.fill(issueWidth)(false.B)))

  queryFresh := queryEnabled
  when(queryEnabled) {
    queryPc := io.qaddrs(0)
    queryHistoryReg := history
    for (i <- 0 until issueWidth) {
      queryBankSelectReg(i) := querySubaddr(i)(bankSelectBits - 1, 0)
      queryLocalHistoryReg(i) := queryLocalHistory(i)
      queryGshareReg(i) := queryGshare(i)
      queryUseGshareReg(i) := queryUseGshare(i)
      queryPhtValidReg(i) := queryPhtValid(i)
    }
  }

  val phtCounter = Wire(Vec(issueWidth, UInt(3.W)))
  val freshPrediction = Wire(Vec(issueWidth, UInt(32.W)))
  for (i <- 0 until issueWidth) {
    phtCounter(i) := phtBanks(0).rdata
    for (bank <- 1 until bankCount) {
      when(queryBankSelectReg(i) === bank.U) {
        phtCounter(i) := phtBanks(bank).rdata
      }
    }

    val phtTaken = Mux(
      queryPhtValidReg(i),
      phtCounter(i) >= 2.U,
      false.B,
    )
    val differentResult = queryGshareReg(i) =/= phtTaken
    val selected = Mux(queryUseGshareReg(i), queryGshareReg(i), phtTaken)
    freshPrediction(i) := Cat(
      queryLocalHistoryReg(i),
      queryHistoryReg,
      differentResult,
      queryUseGshareReg(i),
      selected,
    )
  }

  // Keep the latest prediction available while an update blocks new PHT reads.
  val heldPrediction =
    RegInit(VecInit(Seq.fill(issueWidth)(0.U(32.W))))
  val heldPc = RegInit(0.U(32.W))
  val heldValid = RegInit(false.B)

  for (i <- 0 until issueWidth) {
    io.out(i) := Mux(queryFresh, freshPrediction(i), heldPrediction(i))
  }
  io.outPc := Mux(queryFresh, queryPc, heldPc)
  io.outValid := queryFresh || heldValid
  when(queryFresh) {
    heldPrediction := freshPrediction
    heldPc := queryPc
    heldValid := true.B
  }

  val incomingUpdate = Wire(new PHTUpdate)
  incomingUpdate.subaddr := io.uaddr(PBIT + 1, 2)
  incomingUpdate.localHistory := io.ures(PBIT + KBIT + 3, PBIT + 4)
  incomingUpdate.actualTaken := io.ures(1) ^ io.ures(0)

  when(io.uvalid) {
    val reverted = io.ures(0)
    val used = io.ures(2)
    val differentResult = io.ures(3)
    val globalHistory = io.ures(PBIT + 3, 4)
    val actualTaken = incomingUpdate.actualTaken
    val subaddr = incomingUpdate.subaddr
    val localHistory = incomingUpdate.localHistory

    when(reverted) {
      when(differentResult) {
        score(subaddr) := Mux(
          used,
          Mux(score(subaddr) === 0.U, 0.U, score(subaddr) - 1.U),
          Mux(score(subaddr) === 3.U, 3.U, score(subaddr) + 1.U),
        )
      }
    }.otherwise {
      when(differentResult) {
        score(subaddr) := Mux(
          used,
          Mux(score(subaddr) === 3.U, 3.U, score(subaddr) + 1.U),
          Mux(score(subaddr) === 0.U, 0.U, score(subaddr) - 1.U),
        )
      }
    }

    val gshareIndex = subaddr ^ globalHistory
    val oldGshare = Gshare_status(gshareIndex)
    Gshare_status(gshareIndex) := Mux(
      actualTaken,
      Mux(oldGshare === 3.U, 3.U, oldGshare + 1.U),
      Mux(oldGshare === 0.U, 0.U, oldGshare - 1.U),
    )
    history := Cat(globalHistory(PBIT - 2, 0), actualTaken)
    LHT(subaddr) := Cat(localHistory(KBIT - 2, 0), actualTaken)
  }

  val updateQueue =
    RegInit(VecInit(Seq.fill(queueDepth)(0.U.asTypeOf(new PHTUpdate))))
  val queueHead = RegInit(0.U(queueIndexBits.W))
  val queueTail = RegInit(0.U(queueIndexBits.W))
  val queueCount = RegInit(0.U(queueCountBits.W))
  val updateState = RegInit(sIdle)

  val activeSubaddr = RegInit(0.U(PBIT.W))
  val activeLocalHistory = RegInit(0.U(KBIT.W))
  val activeTaken = RegInit(false.B)

  val queueNotEmpty = queueCount =/= 0.U
  val launchFromQueue = updateState === sIdle && queueNotEmpty
  val launchIncoming =
    updateState === sIdle && !queueNotEmpty && io.uvalid
  val enqueueIncoming = io.uvalid && !launchIncoming
  val launchUpdate = launchFromQueue || launchIncoming
  val updateToRead = Mux(launchFromQueue, updateQueue(queueHead), incomingUpdate)

  when(enqueueIncoming) {
    assert(
      queueCount < queueDepth.U || launchFromQueue,
      "PHT update queue overflow",
    )
  }

  when(launchUpdate) {
    activeSubaddr := updateToRead.subaddr
    activeLocalHistory := updateToRead.localHistory
    activeTaken := updateToRead.actualTaken
    updateState := sUpdateRead

    val updateBankAddress = Cat(
      updateToRead.subaddr(PBIT - 1, bankSelectBits),
      updateToRead.localHistory,
    )
    for (bank <- 0 until bankCount) {
      when(updateToRead.subaddr(bankSelectBits - 1, 0) === bank.U) {
        phtBanks(bank).en := true.B
        phtBanks(bank).we := false.B
        phtBanks(bank).addr := updateBankAddress
      }
    }
  }

  val activePhtIndex = Wire(UInt(log2Ceil(KSIZE).W))
  val activeBankAddress = Wire(UInt(bankAddressBits.W))
  activePhtIndex := Cat(activeSubaddr, activeLocalHistory)
  activeBankAddress := Cat(
    activeSubaddr(PBIT - 1, bankSelectBits),
    activeLocalHistory,
  )

  when(updateState === sUpdateRead) {
    val activeBank = activeSubaddr(bankSelectBits - 1, 0)
    val oldCounter = Wire(UInt(3.W))
    oldCounter := phtBanks(0).rdata
    for (bank <- 1 until bankCount) {
      when(activeBank === bank.U) {
        oldCounter := phtBanks(bank).rdata
      }
    }
    // An untrained SRAM entry contains X. Start it from the architectural
    // weak-not-taken value used by the previous register PHT.
    val effectiveOldCounter =
      Mux(phtValid(activePhtIndex), oldCounter, 1.U)
    val newCounter = Mux(
      activeTaken,
      Mux(effectiveOldCounter === 3.U, 3.U, effectiveOldCounter + 1.U),
      Mux(effectiveOldCounter === 0.U, 0.U, effectiveOldCounter - 1.U),
    )

    for (bank <- 0 until bankCount) {
      when(activeBank === bank.U) {
        phtBanks(bank).en := true.B
        phtBanks(bank).we := true.B
        phtBanks(bank).wmask := 1.U
        phtBanks(bank).addr := activeBankAddress
        phtBanks(bank).wdata := newCounter
      }
    }
    phtValid(activePhtIndex) := true.B
    updateState := sIdle
  }

  when(launchFromQueue) {
    queueHead := queueHead + 1.U
  }
  when(enqueueIncoming) {
    updateQueue(queueTail) := incomingUpdate
    queueTail := queueTail + 1.U
  }
  queueCount :=
    queueCount + enqueueIncoming.asUInt - launchFromQueue.asUInt

  io.stall := io.uvalid || updateState =/= sIdle || queueNotEmpty
  assert(
    !(io.queryValid && io.stall),
    "PHT query must be suppressed while a training update owns the banks",
  )
}
