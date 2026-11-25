//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Cache

import Config._
import Config.CPUConfig._
import chisel3._
import chisel3.util._
import cpu2026._

/** Tag SRAM 中条目元数据的编码方式。
  *
  * 最低位固定为 valid；D-Cache 的下一位为 dirty。高比特保存 tag。
  * I-Cache 不生成 dirty 位，避免为只读存储结构保留无用状态。
  */
object CacheEntry {
  def metaWidth(hasDirty: Boolean): Int = if (hasDirty) 2 else 1

  def width(tagBits: Int, hasDirty: Boolean): Int =
    tagBits + metaWidth(hasDirty)

  def pack(
    tagValue: UInt,
    valid: Bool,
    dirty: Bool,
    hasDirty: Boolean
  ): UInt = {
    val metadata = if (hasDirty) Cat(dirty, valid) else valid.asUInt
    Cat(tagValue, metadata)
  }

  def tag(entry: UInt, tagBits: Int, hasDirty: Boolean): UInt = {
    val metadataBits = metaWidth(hasDirty)
    entry(tagBits + metadataBits - 1, metadataBits)
  }

  def valid(entry: UInt): Bool = entry(0)

  def dirty(entry: UInt, hasDirty: Boolean): Bool =
    if (hasDirty) entry(1) else false.B
}

/** D-Cache 回填时的写入口数据选择。
  *
  * store miss 先读取完整 word，再由 storeMask 逐字节决定每个 lane 来自
  * store 还是内存。storeData 本身保持不变，mask 只在 Cache 写入口解释。
  */
object CacheData {
  def mergeStore(
    memoryData: UInt,
    storeData: UInt,
    storeMask: UInt
  ): UInt = {
    val lanes = (0 until 4).reverse.map { lane =>
      Mux(
        storeMask(lane),
        storeData(8 * lane + 7, 8 * lane),
        memoryData(8 * lane + 7, 8 * lane)
      )
    }
    Cat(lanes)
  }
}

/** 四路组相联 Cache。
  *
  * I-Cache 与 D-Cache 共用查询和替换流程。`hasDirty=false` 的实例不包含
  * dirty 位、脏块写回寄存器和 store 更新逻辑；因此指令 Cache 的 tag 条目
  * 只保存 `{valid, tag}`。D-Cache 的 store miss 先读取完整 word，因此缓存的
  * 每个有效 word 始终完整，dirty 只需一个 bit 表示整个 word 是否被修改。
  *
  * SRAM 没有复位能力，所以 valid 必须在上电后显式清零。清零期间
  * `out_ready=false`，父模块必须推迟请求。正常查询仍在请求后的下一拍返回，
  * 不因 valid/dirty 移入 SRAM 而增加额外延迟。
  */
class Cache(val offsetBIT: Int, val hasDirty: Boolean) extends Module {
  val io = IO(new Bundle {
    val load_valid = Input(Bool())
    val need_load = Input(Bool())
    val store_valid = Input(Bool())
    // 地址右移 offsetBIT 后的 Cache 字地址，仅保留 tag 与 set 所需位。
    val addr = Input(UInt((32 - offsetBIT).W))
    val wdata = Input(UInt(32.W))
    val wmask = Input(UInt(4.W))

    val new_valid = Input(Bool())
    val new_data = Input(UInt(32.W))
    val clear_status = Input(Bool())
    val store_received = Input(Bool())

    val out_ready = Output(Bool())
    val out_hit = Output(Bool())
    val out_rdata = Output(UInt(32.W))

    val out_wvalid = Output(Bool())
    val out_waddr = Output(UInt(32.W))
    val out_wdata = Output(UInt(32.W))
  })

  val tagBits = 32 - setBIT - offsetBIT
  val entryBits = CacheEntry.width(tagBits, hasDirty)

  def packEntry(tagValue: UInt, valid: Bool, dirty: Bool): UInt =
    CacheEntry.pack(tagValue, valid, dirty, hasDirty)

  def entryTag(entry: UInt): UInt =
    CacheEntry.tag(entry, tagBits, hasDirty)

  def entryValid(entry: UInt): Bool = CacheEntry.valid(entry)

  def entryDirty(entry: UInt): Bool =
    CacheEntry.dirty(entry, hasDirty)

  io.out_hit := false.B
  io.out_rdata := 0.U
  io.out_wvalid := false.B
  io.out_waddr := 0.U
  io.out_wdata := 0.U

  // age 是每次 hit 都会更新的热状态；继续使用寄存器可避免同步 SRAM
  // 单端口在读旧 age 和写新 age 之间产生读写冲突。
  //
  // 每个 set 的四路 age 打包成一个字。这样按 set 索引时 Verilator 只需为
  // 一个 8 位寄存器生成选择逻辑，而不是分别展开四组 2 位选择。
  val ageBits = 2
  val ageWordWidth = lineNum * ageBits
  val age = RegInit(
    VecInit(Seq.fill(setSIZE)(0.U(ageWordWidth.W)))
  )

  def ageAt(word: UInt, way: Int): UInt =
    word((way + 1) * ageBits - 1, way * ageBits)

  def packAges(ages: Seq[UInt]): UInt = Cat(ages.reverse)

  val needTag = Reg(UInt(tagBits.W))
  val lstLoad = RegInit(false.B)
  val lstStore = RegInit(false.B)
  // 保存未完成请求的 store 数据，避免父模块改变输入后丢失写内容。
  val lstData = RegInit(0.U(32.W))
  val lstMask = RegInit(0.U(4.W))
  val lstIdx = Reg(UInt(setBIT.W))
  // Miss 回填可能等待多拍。查询请求本身只在本拍有效，因此单独保存
  // 回填所需状态，避免后续默认输入把 tag、set 和 store 信息覆盖。
  val fillTag = if (hasDirty) Some(Reg(UInt(tagBits.W))) else None
  val fillIdx = if (hasDirty) Some(Reg(UInt(setBIT.W))) else None
  val fillStore = if (hasDirty) Some(RegInit(false.B)) else None
  val fillData = if (hasDirty) Some(RegInit(0.U(32.W))) else None
  val fillMask = if (hasDirty) Some(RegInit(0.U(4.W))) else None
  // 0: 空闲；1: 等待内存回填；2: 等待 D-Cache 脏块写回。
  val waiting = RegInit(0.U(2.W))

  // 查询结果中的 tag/data 与 valid/dirty 快照。
  //
  // SRAM 在 en=0 或写周期时 rdata 不保证有效，因此回填可能发生在若干拍
  // 以后时不能再次直接使用 rdata。快照只保存最近一次查询的四路结果，
  // 不保存整张元数据表。
  val tags = Reg(Vec(lineNum, UInt(tagBits.W)))
  val datas = Reg(Vec(lineNum, UInt(32.W)))
  val validSnapshots =
    RegInit(VecInit(Seq.fill(lineNum)(false.B)))
  val dirtySnapshots =
    if (hasDirty) {
      Some(RegInit(VecInit(Seq.fill(lineNum)(false.B))))
    } else {
      None
    }

  def dirtySnapshot(way: Int): Bool =
    dirtySnapshots.map(_(way)).getOrElse(false.B)

  val writebackValid =
    if (hasDirty) Some(RegInit(false.B)) else None
  val preWaddr =
    if (hasDirty) Some(Reg(UInt(32.W))) else None
  val preWdata =
    if (hasDirty) Some(Reg(UInt(32.W))) else None

  io.out_wvalid := writebackValid.getOrElse(false.B)
  io.out_waddr := preWaddr.getOrElse(0.U(32.W))
  io.out_wdata := preWdata.getOrElse(0.U(32.W))

  val tagRams = Seq.fill(lineNum)(
    Module(new SRAM(setSIZE, entryBits, entryBits))
  )
  val dataRams = Seq.fill(lineNum)(
    Module(new SRAM(setSIZE, 32, 8))
  )

  for (i <- 0 until lineNum) {
    tagRams(i).clk := clock
    dataRams(i).clk := clock
    tagRams(i).en := false.B
    dataRams(i).en := false.B
    tagRams(i).we := false.B
    tagRams(i).wmask := 0.U
    tagRams(i).addr := 0.U
    tagRams(i).wdata := 0.U
    dataRams(i).we := false.B
    dataRams(i).wmask := 0.U
    dataRams(i).addr := 0.U
    dataRams(i).wdata := 0.U
  }

  val tag = Wire(UInt(tagBits.W))
  val set_idx = Wire(UInt(setBIT.W))
  val missed = Wire(Bool())
  val flag = Wire(UInt(3.W))
  val write_data = Wire(Bool())
  val directStore = Wire(Bool())
  val data = Wire(UInt(32.W))
  val isdirty = Wire(Bool())

  write_data := false.B
  directStore := false.B
  missed := false.B
  flag := 0.U
  data := 0.U
  isdirty := false.B
  set_idx := io.addr(setBIT - 1, 0)
  tag := io.addr(31 - offsetBIT, setBIT)

  // SRAM 不提供复位值。四个 way 并行清零，共 setSIZE 拍。
  val initDone = RegInit(false.B)
  val initIndex = RegInit(0.U(setBIT.W))
  io.out_ready := initDone

  when (!initDone) {
    for (i <- 0 until lineNum) {
      tagRams(i).en := true.B
      tagRams(i).we := true.B
      tagRams(i).addr := initIndex
      tagRams(i).wdata := 0.U
      tagRams(i).wmask := 1.U
    }
    when (initIndex === (setSIZE - 1).U) {
      initDone := true.B
    }.otherwise {
      initIndex := initIndex + 1.U
    }
  }.otherwise {
    // 请求到达时同时读取 tag 和 data。valid/dirty 与 tag 位于同一个
    // SRAM word，因此下一拍即可完成完整命中判断，不需要额外查询元数据。
    when (waiting === 0.U && (io.load_valid || io.store_valid)) {
      for (i <- 0 until lineNum) {
        tagRams(i).en := true.B
        tagRams(i).we := false.B
        tagRams(i).addr := set_idx
        dataRams(i).en := true.B
        dataRams(i).we := false.B
        dataRams(i).addr := set_idx
      }
    }

    val lookupValid = Wire(Vec(lineNum, Bool()))
    lookupValid := 0.U.asTypeOf(lookupValid)

    when (
      waiting === 0.U ||
        (waiting === 1.U && io.clear_status === true.B)
    ) {
      waiting := 0.U
      needTag := tag
      lstLoad := io.load_valid
      lstStore := io.store_valid
      lstData := io.wdata
      lstMask := io.wmask
      lstIdx := set_idx
      val queryAgeWord = Wire(UInt(ageWordWidth.W))
      queryAgeWord := age(lstIdx)
      when ((lstLoad | lstStore) === true.B) {
        flag := 0.U
        missed := true.B
        val thresholdAge = WireDefault(0.U(2.W))
        for (i <- 0 until lineNum) {
          val entry = tagRams(i).rdata
          lookupValid(i) := entryValid(entry)
          when (lookupValid(i) && entryTag(entry) === needTag) {
            io.out_hit := true.B
            flag := (i + 1).U
            missed := false.B
            io.out_rdata := dataRams(i).rdata
            thresholdAge := ageAt(queryAgeWord, i)
          }
          tags(i) := entryTag(entry)
          validSnapshots(i) := entryValid(entry)
          dirtySnapshots.foreach(_(i) := entryDirty(entry))
          datas(i) := dataRams(i).rdata
        }
        when (missed === false.B) {
          val updatedAges = Wire(Vec(lineNum, UInt(ageBits.W)))
          for (i <- 0 until lineNum) {
            val oldAge = ageAt(queryAgeWord, i)
            when (flag === (i + 1).U) {
              updatedAges(i) := 0.U
            }.elsewhen (
              lookupValid(i) && oldAge < thresholdAge
            ) {
              updatedAges(i) := oldAge + 1.U
            }.otherwise {
              updatedAges(i) := oldAge
            }
          }
          age(lstIdx) := packAges(updatedAges)
        }

        when (missed === false.B) {
          when (hasDirty.B && lstStore === true.B) {
            for (i <- 0 until lineNum) {
              when ((i + 1).U === flag) {
                dataRams(i).en := true.B
                dataRams(i).we := true.B
                dataRams(i).addr := lstIdx
                dataRams(i).wdata := lstData
                dataRams(i).wmask := lstMask

                if (hasDirty) {
                  // 当前查询已读出完整 tag word，可以直接整字改写 dirty，
                  // 不需要额外的 SRAM 读或额外流水级。
                  tagRams(i).en := true.B
                  tagRams(i).we := true.B
                  tagRams(i).addr := lstIdx
                  tagRams(i).wdata :=
                    packEntry(entryTag(tagRams(i).rdata), true.B, true.B)
                  tagRams(i).wmask := 1.U
                  dirtySnapshots.foreach(_(i) := true.B)
                }
              }
            }
          }.elsewhen ((io.load_valid | io.store_valid) === true.B) {
            for (i <- 0 until lineNum) {
              tagRams(i).en := true.B
              tagRams(i).we := false.B
              tagRams(i).addr := set_idx
              dataRams(i).en := true.B
              dataRams(i).we := false.B
              dataRams(i).addr := set_idx
            }
          }
        }.otherwise {
          // 全字 store miss 已经知道整行的最终内容，直接安装即可。
          // 部分写 miss 若直接占用 victim，未写字节会来自旧 victim，
          // 因此必须先读取完整 word，稍后在写入口按 mask 合并。
          if (hasDirty) {
            when (lstStore === true.B && lstMask === 15.U) {
              directStore := true.B
              write_data := true.B
              data := lstData
              isdirty := true.B
            }.otherwise {
              fillTag.foreach(_ := needTag)
              fillIdx.foreach(_ := lstIdx)
              fillStore.foreach(_ := lstStore)
              fillData.foreach(_ := lstData)
              fillMask.foreach(_ := lstMask)
              lstLoad := false.B
              waiting := 1.U
            }
          } else {
            // I-Cache 的回填时序保持原有实现；本次只调整 D-Cache。
            when (lstStore === true.B) {
              write_data := true.B
              data := lstData
              lstLoad := false.B
              lstStore := false.B
              isdirty := hasDirty.B
            }.otherwise {
              waiting := 1.U
            }
          }
        }
      }

    }.elsewhen (waiting === 1.U) {
      when (io.new_valid === true.B) {
        write_data := true.B
        if (hasDirty) {
          data := Mux(
            fillStore.get,
            CacheData.mergeStore(
              io.new_data,
              fillData.get,
              fillMask.get
            ),
            io.new_data
          )
        } else {
          data := io.new_data
        }
        waiting := 0.U
        lstLoad := false.B
        if (hasDirty) {
          isdirty := fillStore.get
        } else {
          isdirty := false.B
        }
        lstStore := false.B
      }
    }.otherwise {
      if (hasDirty) {
        when (io.store_received === true.B) {
          waiting := 0.U
          writebackValid.foreach(_ := false.B)
        }
      }
    }

    // 回填可能发生在 load miss 的若干拍之后。store miss 当拍回填时 rdata
    // 仍有效，load miss 延迟回填时则使用查询拍保存的快照。
    when (write_data === true.B) {
      val wayValid = Wire(Vec(lineNum, Bool()))
      val wayAge = Wire(Vec(lineNum, UInt(2.W)))
      val victimTag = Wire(Vec(lineNum, UInt(tagBits.W)))
      val victimData = Wire(Vec(lineNum, UInt(32.W)))
      val victimDirty = Wire(Vec(lineNum, Bool()))
      val fillTagOnThisCycle =
        if (hasDirty) {
          Mux(
            directStore,
            needTag,
            Mux(fillStore.get, fillTag.get, needTag)
          )
        } else {
          needTag
        }
      val fillIdxOnThisCycle =
        if (hasDirty) {
          Mux(
            directStore,
            lstIdx,
            Mux(fillStore.get, fillIdx.get, lstIdx)
          )
        } else {
          lstIdx
        }

      val fillAgeWord = Wire(UInt(ageWordWidth.W))
      fillAgeWord := age(fillIdxOnThisCycle)

      for (i <- 0 until lineNum) {
        wayValid(i) :=
          Mux(missed, entryValid(tagRams(i).rdata), validSnapshots(i))
        wayAge(i) := ageAt(fillAgeWord, i)
        victimTag(i) :=
          Mux(missed, entryTag(tagRams(i).rdata), tags(i))
        victimData(i) :=
          Mux(missed, dataRams(i).rdata, datas(i))
        victimDirty(i) :=
          Mux(missed, entryDirty(tagRams(i).rdata), dirtySnapshot(i))
      }

      val chosen = CacheReplacement.selectVictim(wayValid, wayAge)
      val updatedAges = Wire(Vec(lineNum, UInt(ageBits.W)))

      for (i <- 0 until lineNum) {
        when (i.U === chosen) {
          if (hasDirty) {
            when (victimDirty(i) === true.B) {
              writebackValid.foreach(_ := true.B)
              preWaddr.foreach(
                _ := (Cat(victimTag(i), fillIdxOnThisCycle) << offsetBIT.U)
              )
              preWdata.foreach(_ := victimData(i))
              waiting := 2.U
            }
          }

          tagRams(i).en := true.B
          tagRams(i).we := true.B
          tagRams(i).addr := fillIdxOnThisCycle
          tagRams(i).wdata := packEntry(fillTagOnThisCycle, true.B, isdirty)
          tagRams(i).wmask := 1.U

          dataRams(i).en := true.B
          dataRams(i).we := true.B
          dataRams(i).addr := fillIdxOnThisCycle
          dataRams(i).wdata := data
          if (hasDirty) {
            // 只有 store miss 会安装一个完整的合并 word；load miss 保持
            // 原有 mask 行为，避免改变已验证的数据读取路径。
            dataRams(i).wmask :=
              Mux(directStore || fillStore.get, 15.U, lstMask)
          } else {
            dataRams(i).wmask := lstMask
          }

          updatedAges(i) := 0.U
        }.otherwise {
          val oldAge = ageAt(fillAgeWord, i)
          when (wayValid(i) === true.B) {
            updatedAges(i) := Mux(
              oldAge === (lineNum - 1).U,
              oldAge,
              oldAge + 1.U
            )
          }.otherwise {
            updatedAges(i) := oldAge
          }
        }
      }
      age(fillIdxOnThisCycle) := packAges(updatedAges)
    }
  }
}
