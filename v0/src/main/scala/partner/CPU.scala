//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package cpu.main
import Config._
import Config.CPUConfig._
import Op._
import CDB._
import RS._
import ROB._
import Register._
import Cache._
import Decoder._
import Executer._
import Committer._
import Predictor._

import chisel3._
import chisel3.util._
import Config.CPUConfig.issueWidth

class CPU extends Module{//priority: ready instruction< read data < write data < Dcache write back
    val io=IO(new Bundle{
        val araddr  = Output(UInt(32.W))
        val arvalid = Output(Bool())
        val arready = Input(Bool())

        val rdata   = Input(UInt(32.W))
        val rresp   = Input(UInt(2.W))
        val rvalid  = Input(Bool())
        val rready  = Output(Bool())

        val awaddr  = Output(UInt(32.W))
        val awvalid = Output(Bool())
        val awready = Input(Bool())

        val wdata   = Output(UInt(32.W))
        val wstrb   = Output(UInt(4.W))
        val wvalid  = Output(Bool())
        val wready  = Input(Bool())

        val bresp   = Input(UInt(2.W))
        val bvalid  = Input(Bool())
        val bready  = Output(Bool())

    })
    val init = Wire(new Register())
    init.value := 0.U(32.W)
    init.prod  := -1.S(32.W)
    val regs = RegInit(VecInit(Seq.fill(32)(init)))
    val pc = RegInit(init)
    
    val clk=RegInit(0.U(32.W))
    val halt=RegInit(false.B)
    val waiting_val=Reg(UInt(32.W))
    
    val flush=RegInit(false.B)
    val flush_val=Reg(SInt(32.W))
    val newaddr=Reg(UInt(32.W))
    // Committer 的 flush 在下一拍处理；JALR 的返回地址写回与 flush
    // 同拍产生，因此必须一起寄存，不能只保存 PC 和 flush 标记。
    val flush_reg_valid = RegInit(false.B)
    val flush_reg_id = RegInit(0.U(5.W))
    val flush_reg_value = RegInit(0.U(32.W))
    val flush_reg_prod = RegInit(0.U(32.W))
    // Fetcher 的下一拍 PC。跳转、分支预测和 flush 都通过该路径更新 PC。
    val new_pc_val = Wire(UInt(32.W))
    // 寄存一拍清除 Icache 的未完成预查询状态，避免 clear_status 经
    // Icache 输出、预译码再回到自身形成组合环。
    val fetch_clear_status = RegInit(false.B)
    val Icache_out_num = Wire(UInt(32.W))

    regs(0).value:=0.U(32.W)
    regs(0).prod := -1.S(32.W)
    val opcode=Wire(SInt(32.W))

    // 四条指令槽各有一个 Icache 实例，按 PC[3:2] 循环映射到连续四字。
    // I-Cache 不维护 dirty 位；只有 D-Cache 生成脏块写回逻辑。
    val Icache=Seq.tabulate(issueWidth) { i =>
        Module(new Cache(4, hasDirty = false))
    }//Icache(i) manages start addr mod 16 = 4i
    val Dcache=Module(new Cache(2, hasDirty = true))
    val committer=Module(new Committer)
    val alus = Seq.tabulate(ALU_NUM) { i =>Module(new ALU(i))}
    val lsu=Module(new LSU)
    val bru=Module(new BRU)
    val mult  = Module(new Mult)
    val div  = Module(new Divider)
    val issuer = Module(new _root_.Issuer.Issuer)
    val decoder = Module(new Decoder)
    val predictor = Module(new Predictor)
    val fetchControl = Module(new _root_.FetchControl.FetchControl)

    // Module inputs and combinational wires have no storage. Define safe
    // values for every inactive control path; later assignments override
    // these defaults when a module is actually used.
    io.araddr := 0.U
    io.arvalid:=false.B
    io.rready:=true.B
    io.awaddr := 0.U
    io.awvalid:=false.B
    io.wdata := 0.U
    io.wstrb := 0.U
    io.wvalid:=false.B
    io.bready:=true.B

    Icache_out_num := 0.U
    opcode := 0.S

    halt:=halt|committer.io.out_halt
    flush:=committer.io.out_flush
    flush_val:=committer.io.out_flushval.asSInt
    newaddr:=committer.io.out_newaddr
    flush_reg_valid:=committer.io.out_reg_valid
    flush_reg_id:=committer.io.out_reg_id
    flush_reg_value:=committer.io.out_reg_val
    flush_reg_prod:=committer.io.out_reg_prod
    // Committer 回滚必须丢弃 Decoder 中尚未进入 Issuer 的年轻指令。
    // JALR 同样在 Commit 点才产生 redirect，因此不再需要 Issue 特例。
    decoder.io.flush_valid := flush
    decoder.io.out_ready := issuer.io.in_ready
    issuer.io.in_valid := decoder.io.out_valid
    issuer.io.predictor_result := decoder.io.out_prediction
    
    clk:=clk+1.U(32.W)
    
    //initialization
    for (i<-0 until ALU_NUM){
        alus(i).io.clear_valid:=false.B
    }
    bru.io.clear_valid:=false.B
    lsu.io.clear_valid:=false.B
    committer.io.clear_valid:=false.B
    issuer.io.valid_num:=0.U
    decoder.io.in_valid:=false.B
    decoder.io.valid_num:=0.U

    for (i<-0 until issueWidth){
        Icache(i).io.addr:=0.U((32-4).W)
        Icache(i).io.need_load:=false.B
        Icache(i).io.wdata:=0.U
        Icache(i).io.wmask:=0.U
        Icache(i).io.new_data:=0.U
        Icache(i).io.load_valid:=false.B
        Icache(i).io.store_valid:=false.B
        Icache(i).io.new_valid:=false.B
        Icache(i).io.store_received:=false.B
        Icache(i).io.clear_status:=fetch_clear_status
    }
    issuer.io.flush_valid:=false.B
    Dcache.io.addr:=0.U((32-2).W)
    Dcache.io.need_load:=false.B
    Dcache.io.wdata:=0.U
    Dcache.io.wmask:=0.U
    Dcache.io.new_data:=0.U
    Dcache.io.load_valid:=false.B
    Dcache.io.store_valid:=false.B
    Dcache.io.new_valid:=false.B
    Dcache.io.store_received:=false.B
    Dcache.io.clear_status:=false.B

    // Decoder input payloads are irrelevant for slots without a valid
    // instruction, but their ports still require deterministic drivers.
    decoder.io.id := 0.U.asTypeOf(decoder.io.id)
    decoder.io.opcode := 0.U.asTypeOf(decoder.io.opcode)

    // qaddrs is the next fetch group, not the currently executing PC. The
    // predictor and I-cache issue their synchronous reads together so both
    // results arrive in the same cycle.
    for (i <- 0 until issueWidth) {
        predictor.io.qaddrs(i) := 0.U
        fetchControl.io.pcs(i) := pc.value + (4 * i).U
    }
    fetchControl.io.instructions := 0.U.asTypeOf(fetchControl.io.instructions)
    fetchControl.io.hits := 0.U.asTypeOf(fetchControl.io.hits)

    // Predictor queries and updates are disabled unless the main path
    // explicitly enables them below.
    predictor.io.queryValid := false.B
    predictor.io.uvalid := false.B
    predictor.io.queryPc := 0.U
    predictor.io.uaddr := 0.U
    predictor.io.ures := 0.U

    // Issuer receives instruction and machine state only on the main path.
    issuer.io.id := 0.U.asTypeOf(issuer.io.id)
    issuer.io.cur_op := 0.U.asTypeOf(issuer.io.cur_op)
    issuer.io.reg := 0.U.asTypeOf(issuer.io.reg)
    issuer.io.robs := 0.U.asTypeOf(issuer.io.robs)
    issuer.io.executer_space := 0.U.asTypeOf(issuer.io.executer_space)
    issuer.io.committer_space := 0.U

    // Execution units and the committer accept no work by default.
    for (i <- 0 until ALU_NUM) {
        alus(i).io.valid_num := 0.U
        alus(i).io.rs := 0.U.asTypeOf(alus(i).io.rs)
        alus(i).io.cdbs := 0.U.asTypeOf(alus(i).io.cdbs)
        alus(i).aluIO.mult_valid := 0.U
        alus(i).aluIO.mult_res := 0.U
        alus(i).aluIO.mult_received := 0.U
        alus(i).aluIO.div_valid := 0.U
        alus(i).aluIO.div_res := 0.U
        alus(i).aluIO.div_received := 0.U
    }

    lsu.io.valid_num := 0.U
    lsu.io.rs := 0.U.asTypeOf(lsu.io.rs)
    lsu.io.cdbs := 0.U.asTypeOf(lsu.io.cdbs)
    lsu.lsuIO.robs := 0.U.asTypeOf(lsu.lsuIO.robs)
    lsu.lsuIO.l := 0.U
    lsu.lsuIO.r := 0.U
    lsu.lsuIO.addr_valid := false.B
    lsu.lsuIO.addr_received := false.B
    lsu.lsuIO.addr_value := 0.U

    bru.io.valid_num := 0.U
    bru.io.rs := 0.U.asTypeOf(bru.io.rs)
    bru.io.cdbs := 0.U.asTypeOf(bru.io.cdbs)

    committer.io.valid_num := 0.U
    committer.io.in_robs := 0.U.asTypeOf(committer.io.in_robs)
    committer.io.cdbs := 0.U.asTypeOf(committer.io.cdbs)
    committer.io.store_received := false.B

    mult.io.valid := 0.U
    mult.io.a := 0.U
    mult.io.b := 0.U
    mult.io.op := false.B
    mult.io.clear_valid := false.B
    div.io.valid := 0.U
    div.io.a := 0.U
    div.io.b := 0.U
    div.io.op := false.B
    div.io.clear_valid := false.B

    // ======================== Fetcher ========================
    // 当前实现没有独立 Fetcher 模块，取指控制内联在 CPU 中。
    // pc.value 是当前取指地址，new_pc_val 是下一拍地址。
    //
    // fetcher_waiting:
    //   0 = 查询 Icache；全部 miss 时发起 AXI 读
    //   1 = 等待 AR 握手
    //   2 = 等待 R 数据并交给 Decoder
    //
    // 注意：该状态当前还隐含承担“在途 AXI 读事务”的跟踪。
    // redirect/flush 只能在确认没有未完成读事务时直接清零；否则必须先排空
    // 旧请求，避免把旧地址的 rdata 配给重定向后的新 PC。
    val fetcher_waiting = RegInit(0.U(2.W))
    // SRAM 中的 valid 需要显式清零。清零完成前不能发起 I-Cache 查询，
    // 否则内存回填可能被初始化写操作覆盖。
    val cachesReady =
        Icache.map(_.io.out_ready).reduce(_ && _) && Dcache.io.out_ready
    // redirect 到达时若仍有 AXI 读事务，先记住目标，事务排空后再更新 PC。
    val fetcher_redirect_pending = RegInit(false.B)
    val fetcher_redirect_pc = Reg(UInt(32.W))
    // The predictor holds its latest result until a newer query completes.
    // Matching the PC tag prevents a stale group from influencing fetch.
    val current_prediction = Wire(Vec(issueWidth, UInt(32.W)))
    val predictionMatchesPc =
        predictor.io.outValid && predictor.io.outPc === pc.value
    for (i <- 0 until issueWidth) {
        current_prediction(i) :=
            Mux(predictionMatchesPc, predictor.io.out(i), 0.U)
    }
    decoder.io.prediction := current_prediction
    for (i <- 0 until issueWidth) {
        fetchControl.io.predictions(i) := current_prediction(i)(0)
    }

    // 本拍组合决定的 Fetcher 下一状态，同时用于判断是否继续预查 Icache。
    val fetcher_next = Wire(UInt(2.W))
    val fetcher_redirect = Wire(Bool())
    val lsu_waiting=RegInit(0.U(2.W))//0 initial state, 1 waiting for Dcache, 2 waiting for memory, 3 got memory, storing into Dcache
    val lsu_discard_pending = RegInit(false.B)
    val lsu_lst_addr=Reg(UInt(32.W))
    // 0: 空闲；1: 等待 Dcache 命中判断；2: store miss 发起整字读取；
    // 3: store miss 等待读数据并完成带 mask 的 cache 安装。
    val committer_waiting=RegInit(0.U(2.W))
    val Dcache_waiting = RegInit(0.U(2.W))

    // Exit stores bypass the D-cache. Capture the complete store payload at
    // commit time, then let the small state machine below drain it through AXI.
    // Keeping the address and data registered also holds AW/W stable while the
    // corresponding ready signal is low.
    val exit_store_pending = RegInit(false.B)
    val exit_store_addr = RegInit(0.U(32.W))
    val exit_store_data = RegInit(0.U(32.W))
    val exit_store_mask = RegInit(0.U(4.W))
    val exit_store_aw_done = RegInit(false.B)
    val exit_store_w_done = RegInit(false.B)
    val exit_store_commit =
        committer.io.out_store_valid === true.B &&
        committer.io.out_store_addr === "h80000000".U

    fetcher_next := 0.U
    new_pc_val := 0.U
    fetch_clear_status := false.B
    // Only an architectural flush may cancel an outstanding AXI read.
    fetcher_redirect := flush

    // Capture only after the store reaches the commit point, so a store on a
    // mispredicted path can never terminate the program.
    when (exit_store_commit && exit_store_pending===false.B){
        exit_store_pending:=true.B
        exit_store_addr:=committer.io.out_store_addr
        exit_store_data:=committer.io.out_store_value
        exit_store_mask:=committer.io.out_store_mask
        exit_store_aw_done:=false.B
        exit_store_w_done:=false.B
    }

    // D-cache write back is kept outside the normal execution branch so an
    // already-pending dirty line can still drain after the CPU enters exit mode.
    when (halt===false.B||exit_store_pending===true.B){
        //Dcache write back  
        when (Dcache_waiting===0.U)
        {
            when (Dcache.io.out_wvalid===true.B&&
                (exit_store_pending===true.B||(fetcher_waiting===0.U&&lsu_waiting===0.U)))
            {//definitely happens, no need to worry about rolling back
                io.awaddr:=Dcache.io.out_waddr
                io.wdata:=Dcache.io.out_wdata
                io.wstrb:=15.U
                io.wvalid:=true.B
                io.awvalid:=true.B
                Dcache_waiting:=1.U
            }
        }.elsewhen(Dcache_waiting===1.U){
            when (io.wready===true.B&&io.awready===true.B)
            {
                Dcache_waiting:=2.U
            }.otherwise
            {
                io.awaddr:=Dcache.io.out_waddr
                io.wdata:=Dcache.io.out_wdata
                io.wstrb:=15.U
                io.wvalid:=true.B
                io.awvalid:=true.B
            }
        }.otherwise{
            when (io.bvalid===true.B)
            {
                Dcache.io.store_received:=true.B
                Dcache_waiting:=0.U
            }
        }
    }

    when (exit_store_pending===true.B){
        // The normal pipeline is stopped, but old read responses still need to
        // be consumed. The Committer also needs to see this uncached store as
        // complete instead of waiting forever for a D-cache acknowledgement.
        io.rready:=true.B
        committer.io.store_received:=true.B

        // Do not contend with a D-cache writeback on the AXI write channels.
        when (Dcache_waiting===0.U&&Dcache.io.out_wvalid===false.B){
            when (exit_store_aw_done===false.B){
                io.awaddr:=exit_store_addr
                io.awvalid:=true.B
                when (io.awready===true.B){
                    exit_store_aw_done:=true.B
                }
            }
            when (exit_store_w_done===false.B){
                io.wdata:=exit_store_data
                io.wstrb:=exit_store_mask
                io.wvalid:=true.B
                when (io.wready===true.B){
                    exit_store_w_done:=true.B
                }
            }
        }
    }.elsewhen (halt===false.B){
        // Committer 产生的 flush 是最高优先级的 redirect。若此前 AR 已握手，
        // AXI 没有取消机制；清 Cache 状态不能代替等待并排空对应的 R 响应。
        when (flush===true.B){//flush
            issuer.io.flush_valid:=true.B
            issuer.io.valid_num:=0.U
            // JALR 在同一拍既 flush 年轻指令，又提交自己的返回地址。
            // 普通分支 flush 没有寄存器写回，本路径自然跳过。
            when (flush_reg_valid===true.B) {
                val id=Wire(UInt(5.W))
                id:=flush_reg_id
                regs(id).value := flush_reg_value
                when (regs(id).prod.asUInt===flush_reg_prod &&
                    issuer.io.out_prod_valid(id)===false.B) {
                    regs(id).prod := -1.S(32.W)
                }
            }
            for (i<-0 until ALU_NUM)
            {
                alus(i).io.clear_valid:=true.B
            }
            mult.io.clear_valid:=true.B
            div.io.clear_valid:=true.B
            bru.io.clear_valid:=true.B
            lsu.io.clear_valid:=true.B
            for (i<-0 until 32){
                when (regs(i).prod>flush_val){
                    regs(i).prod := -1.S(32.W)
                }
            }
            committer.io.clear_valid:=true.B
            committer_waiting:=0.U
            Dcache_waiting:=0.U
            
            for (i<-0 until issueWidth){
                Icache(i).io.clear_status:=true.B
            }
            Dcache.io.clear_status:=true.B
            // AXI 读一旦发出就不能撤销。保持旧 AR 稳定并排空旧 R，
            // 等最后一个响应握手后才在下一拍切换到 newaddr。
            when (fetcher_waiting===0.U) {
                pc.value:=newaddr
                fetcher_redirect_pending:=false.B
            }.otherwise {
                fetcher_redirect_pending:=true.B
                fetcher_redirect_pc:=newaddr
                when (fetcher_waiting===1.U) {
                    io.araddr:=pc.value
                    io.arvalid:=true.B
                    when (io.arready===true.B) {
                        fetcher_waiting:=2.U
                    }
                }.otherwise {
                    io.rready:=true.B
                    when (io.rvalid===true.B) {
                        fetcher_waiting:=0.U
                    }
                }
            }

            // LSU 若已占用共享读通道，必须继续完成旧事务；响应只丢弃，
            // 避免其残留到 redirect 后的 Fetch 新请求上。
            when (lsu_waiting<=1.U) {
                lsu_waiting:=0.U
                lsu_discard_pending:=false.B
            }.elsewhen (lsu_waiting===2.U) {
                lsu_discard_pending:=true.B
                io.araddr:=lsu_lst_addr
                io.arvalid:=true.B
                when (io.arready===true.B) {
                    lsu_waiting:=3.U
                }
            }.otherwise {
                lsu_discard_pending:=true.B
                io.rready:=true.B
                when (io.rvalid===true.B) {
                    lsu_waiting:=0.U
                    lsu_discard_pending:=false.B
                }
            }

            flush:=false.B
        }.otherwise{//main cycle
            // ==================== Fetcher: 正常取指 ====================
            // 默认保持当前读状态；只有成功取到指令或发生 redirect 时才前进。
            fetcher_next:=fetcher_waiting
            new_pc_val:=pc.value

            when(fetcher_waiting===0.U && fetcher_redirect_pending===true.B)
            {
                // 旧的 AXI 事务已经排空，现在可以安全切到 redirect 目标。
                fetcher_redirect_pending:=false.B
                new_pc_val:=fetcher_redirect_pc
                decoder.io.in_valid:=false.B
            }.elsewhen(fetcher_waiting===0.U && cachesReady)
            {
                // PC[3:2] 决定四个逻辑指令槽在 Icache 实例上的循环起点。
                // 例如起点为 3 时，槽位依次访问 Icache 3、0、1、2。
                for (l<-0 until issueWidth){
                    when (pc.value(3,2)===l.U){
                        for (i<-0 until issueWidth){
                            // Decoder 的有效指令始终紧凑地位于 0..valid_num-1。
                            decoder.io.id(i):=pc.value+(4*i).U
                            decoder.io.opcode(i):=Icache((l+i)%issueWidth).io.out_rdata
                            fetchControl.io.instructions(i):=
                                Icache((l+i)%issueWidth).io.out_rdata
                            fetchControl.io.hits(i):=
                                Icache((l+i)%issueWidth).io.out_hit
                        }
                    }
                }
                decoder.io.valid_num := fetchControl.io.validNum
                decoder.io.in_valid :=
                    fetchControl.io.validNum =/= 0.U && !fetcher_redirect

                // 首字 miss 时从内存取一条指令。Fetcher 与 LSU/Dcache
                // 共享 AXI 读通道，因此必须先确认其他读事务和写回路径空闲。
                when (fetchControl.io.validNum===0.U && !fetcher_redirect &&
                    cachesReady &&
                    Dcache_waiting===false.B &&
                    Dcache.io.out_wvalid===false.B && lsu_waiting===0.U &&
                    lsu.lsuIO.out_valid===false.B &&
                    committer_waiting<2.U){//read instructions from memory
                    io.araddr:=pc.value
                    io.arvalid:=true.B
                    // AR 可能在发请求的同一拍完成握手；只有未握手时才保持状态 1。
                    when (io.arready===true.B) {
                        fetcher_waiting:=2.U
                        fetcher_next:=2.U
                    }.otherwise {
                        fetcher_waiting:=1.U
                        fetcher_next:=1.U
                    }
                }

                // Decoder 接收整组指令后，PC 跳过所有已经交付的前缀命中项。
                // Decoder 不 ready 时保持 PC 不变，实现 fetch->decode 反压。
                when (decoder.io.in_valid && decoder.io.in_ready &&
                    !fetcher_redirect) {
                    when (fetchControl.io.redirect) {
                        new_pc_val := fetchControl.io.redirectTarget
                    }.otherwise {
                        new_pc_val :=
                            pc.value + (fetchControl.io.validNum << 2.U)
                    }
                    when (fetchControl.io.redirect) {
                        // 下一拍清除可能存在的错误路径预查询状态。
                        fetch_clear_status:=true.B
                    }
                }
            }.elsewhen(fetcher_waiting===1.U){
                    // 状态 1：AR 尚未握手。arvalid/araddr 必须保持稳定，
                    // 直到同一周期 arready=1 完成地址阶段。
                    when (io.arready===true.B){
                        fetcher_waiting:=2.U
                        fetcher_next:=2.U
                        io.rready:=fetcher_redirect_pending||fetcher_redirect||decoder.io.in_ready
                    }.otherwise{
                        io.araddr:=pc.value
                        io.arvalid:=true.B
                    }
                }.elsewhen(fetcher_waiting===2.U){
                    // 状态 2：正常取指时 RREADY 跟随 Decoder 的反压；
                    // redirect 或 Dcache store miss 等待读通道时，无条件接收
                    // 在途的旧取指响应。后者避免 Decoder 反压与 store 回填
                    // 互相等待；store 完成前的取指已被禁止，之后会从原 PC 重取。
                    val fetcher_discard =
                        fetcher_redirect_pending||fetcher_redirect||
                        committer_waiting>=2.U
                    fetchControl.io.instructions(0):=io.rdata
                    fetchControl.io.hits(0):=io.rvalid && !fetcher_discard
                    decoder.io.valid_num:=
                        Mux(fetcher_discard,0.U,fetchControl.io.validNum)
                    decoder.io.id(0):=pc.value
                    decoder.io.opcode(0):=io.rdata
                    decoder.io.in_valid:=io.rvalid && !fetcher_discard
                    val fetcher_accept = fetcher_discard||decoder.io.in_ready
                    io.rready:=fetcher_accept

                    // R 握手成功后返回状态 0。正常路径还要把指令交给
                    // Decoder 和 Icache；redirect 路径只负责排空旧响应。
                    when (io.rvalid===true.B&&fetcher_accept===true.B){
                        fetcher_waiting:=0.U
                        fetcher_next:=0.U
                        when (!fetcher_discard) {
                            when (fetchControl.io.redirect) {
                                new_pc_val:=fetchControl.io.redirectTarget
                            }.otherwise {
                                new_pc_val:=pc.value+4.U
                            }
                            when (fetchControl.io.redirect) {
                                fetch_clear_status:=true.B
                            }
                            //write in Icache
                            for (i<-0 until issueWidth)
                            {//other Icaches no longer need to wait for new_data
                                when (pc.value(3,2)===i.U)
                                {
                                    Icache(i).io.new_valid := true.B
                                    Icache(i).io.new_data := io.rdata
                                }
                            }
                        }
                    }
                }

                //handling issuer input
                issuer.io.valid_num:=decoder.io.out_num
                issuer.io.id:=decoder.io.out_id
                issuer.io.cur_op:=decoder.io.out_op

                for (i<-0 until 32){
                    issuer.io.reg(i):=regs(i)
                }

                for (i<-0 until ALU_NUM){
                    issuer.io.executer_space(i):=alus(i).io.out_space
                }
                issuer.io.robs:=committer.io.out_robs
                issuer.io.executer_space(ALU_NUM):=lsu.io.out_space
                issuer.io.executer_space(ALU_NUM+1):=bru.io.out_space
                issuer.io.committer_space:=committer.io.out_space

                //handling issuer output
                //executer input
                val num=Wire(UInt(32.W))
                val cnts=Wire(Vec(issueWidth+1,Vec(ALU_NUM+2,UInt(32.W))))
                num:=issuer.io.out_valid
                for (i<-0 until ALU_NUM+2){
                    cnts(0)(i):=0.U(32.W)
                }
                for (i<-0 until issueWidth){
                    for (j<-0 until ALU_NUM+2){
                        cnts(i+1)(j):=cnts(i)(j)
                    }
                    when (i.U<num){
                        val tp= Wire(UInt(32.W))
                        tp := issuer.io.out_executer_type(i)
                        when (tp<ALU_NUM.U){
                            for (j<-0 until ALU_NUM)//indexing workaround 
                            {
                                when (j.U===tp){
                                    // Four queue entries need a two-bit index.
                                    alus(j).io.rs(cnts(i)(j)(1,0)):=issuer.io.out_executer_RS(i)
                                }
                            }
                        }.elsewhen(tp===ALU_NUM.U){
                            lsu.io.rs(cnts(i)(ALU_NUM)(1,0)):=issuer.io.out_executer_RS(i)
                        }.otherwise{
                            bru.io.rs(cnts(i)(ALU_NUM+1)(1,0)):=issuer.io.out_executer_RS(i)
                        }
                        for (j<-0 until ALU_NUM+2){
                            when (tp===j.U){
                                cnts(i+1)(j):=cnts(i)(j)+1.U
                            }
                        }
                    }
                }
                for (i<-0 until ALU_NUM){
                    alus(i).io.valid_num:=cnts(issueWidth)(i)
                }
                lsu.io.valid_num:=cnts(issueWidth)(ALU_NUM)
                bru.io.valid_num:=cnts(issueWidth)(ALU_NUM+1)
                //committer input
                committer.io.valid_num:=num
                committer.io.in_robs:= issuer.io.out_ROB

                //updating register prods
                for (i<-0 until 32){
                    regs(i).prod:=issuer.io.out_prod(i)
                }
                pc.value:=new_pc_val

                // 状态 0 时提前为 new_pc_val 连续四个字发起 Icache 查询。
                // 下一拍状态 0 使用这些查询结果计算前缀命中数。
                when (fetcher_next===0.U && !fetcher_redirect_pending &&
                    cachesReady)
                {
                    when (!predictor.io.stall) {
                        predictor.io.queryValid := true.B
                    }
                    for (l<-0 until issueWidth){
                        when (new_pc_val(3,2)===l.U){
                            for (i<-0 until issueWidth){
                                predictor.io.queryPc := new_pc_val
                                predictor.io.qaddrs(i) :=
                                    new_pc_val(PBIT+1,2)+i.U(PBIT.W)
                                Icache((i+l)%issueWidth).io.load_valid:=true.B
                                val laneCarry =
                                    if (i == 0) false.B
                                    else new_pc_val(3,2) >= (4-i).U
                                Icache((i+l)%issueWidth).io.addr:=
                                    new_pc_val(31,4)+laneCarry.asUInt
                                // 从内存取回的是完整 32 位指令字；回填时必须写入全部四字节。
                                Icache((i+l)%issueWidth).io.wmask:=15.U
                            }
                        }
                    }
                }

                //handling executer output
                //mult,div
                val mult_used=Wire(Vec(ALU_NUM+1,Bool()))
                val div_used=Wire(Vec(ALU_NUM+1,Bool()))
                mult_used(0):=false.B
                div_used(0):=false.B
                for(i<-0 until ALU_NUM){
                    alus(i).aluIO.mult_valid:=mult.io.ready
                    alus(i).aluIO.mult_res:=mult.io.c
                    alus(i).aluIO.mult_received:=mult.io.received
                    alus(i).aluIO.div_valid:=div.io.ready
                    alus(i).aluIO.div_res:=div.io.c
                    alus(i).aluIO.div_received:=div.io.received
    
                    mult_used(i+1):=mult_used(i)
                    div_used(i+1):=div_used(i)
                    when (alus(i).aluIO.out_mult_valid===true.B&&mult_used(i)===false.B){
                        mult.io.a:=alus(i).aluIO.out_mult_a
                        mult.io.b:=alus(i).aluIO.out_mult_b
                        mult.io.op:=alus(i).aluIO.out_mult_op
                        mult.io.valid:=(i+1).U
                        mult_used(i+1):=true.B
                    }
                    when (alus(i).aluIO.out_div_valid===true.B&&div_used(i)===false.B){
                        div.io.a:=alus(i).aluIO.out_div_a
                        div.io.b:=alus(i).aluIO.out_div_b
                        div.io.op:=alus(i).aluIO.out_div_op
                        div.io.valid:=(i+1).U
                        div_used(i+1):=true.B
                    }
                }

                // LSU 与 Fetcher 共用唯一的 AXI 读通道。
                // 只有 fetcher_next=0 时才允许 LSU 占用该通道，避免两个
                // 状态机同时把同一份 rdata 当成自己的响应。
                lsu.lsuIO.addr_valid:=false.B
                lsu.lsuIO.l:=committer.io.out_l
                lsu.lsuIO.r:=committer.io.out_r
                lsu.lsuIO.robs:=committer.io.out_robs
                when (lsu_waiting===0.U){
                    when (lsu.lsuIO.out_valid===true.B&&Dcache_waiting===0.U&&Dcache.io.out_wvalid===false.B&&committer_waiting===0.U&&committer.io.out_store_valid===false.B){//can load only when no store
                        Dcache.io.load_valid:=true.B
                        Dcache.io.addr := lsu.lsuIO.out_addr(6,0)
                        // Load miss 的返回值是完整 32 位字，回填四个字节。
                        Dcache.io.wmask := 15.U
                        // AXI 返回完整 word，地址必须按 word 对齐；LSU 仍用
                        // 原始字节地址从返回 word 中选择目标 lane。
                        lsu_lst_addr := Cat(lsu.lsuIO.out_addr,0.U(2.W))
                        lsu_waiting:=1.U
                        lsu.lsuIO.addr_received:=true.B
                    }
                }.elsewhen(lsu_waiting===1.U){
                    when (Dcache.io.out_hit===true.B){
                        lsu_waiting:=0.U
                        io.rready:=true.B
                        lsu.lsuIO.addr_valid:=true.B
                        lsu.lsuIO.addr_value:=Dcache.io.out_rdata
                    }.otherwise{//fetcher is not using memory
                        when (fetcher_next===0.U)
                        {
                            io.araddr:=lsu_lst_addr
                            io.arvalid:=true.B
                            when (io.arready===true.B) {
                                io.rready:=true.B
                                lsu_waiting:=3.U
                            }.otherwise {
                                lsu_waiting:=2.U
                            }
                        }.otherwise {
                            lsu_waiting:=2.U
                        }
                    }
                }.elsewhen(lsu_waiting===2.U){
                    // redirect 后的旧事务优先排空；正常请求只在 Fetch 不占用
                    // 地址通道时发送。
                    when (lsu_discard_pending||fetcher_next===0.U){
                        io.araddr:=lsu_lst_addr
                        io.arvalid:=true.B
                        when (io.arready===true.B){
                            io.rready:=true.B
                            lsu_waiting:=3.U
                        }
                    }
                }.otherwise{
                    when (lsu_discard_pending===true.B){
                        io.rready:=true.B
                        when (io.rvalid===true.B){
                            lsu_waiting:=0.U
                            lsu_discard_pending:=false.B
                        }
                    }.otherwise{
                        when (io.rvalid===true.B){
                            lsu_waiting:=0.U
                            lsu.lsuIO.addr_valid:=true.B
                            lsu.lsuIO.addr_value:=io.rdata
                            Dcache.io.new_valid:=true.B
                            Dcache.io.new_data:=io.rdata
                        }.otherwise{
                            io.rready:=true.B
                        }
                    }
                }
                // Fetch 拥有唯一的读事务时，不能让后续 LSU 仲裁覆盖 RREADY。
                // 这里只依赖状态寄存器，避免形成 rready -> fetcher_next 的组合环。
                when (fetcher_waiting===2.U) {
                    io.rready:=fetcher_redirect_pending||fetcher_redirect||
                        committer_waiting>=2.U||decoder.io.in_ready
                }

                //broadcast CDBs
                for (i<-0 until ALU_NUM){
                    for (j<-0 until ALU_NUM){
                        alus(j).io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                    }
                    lsu.io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                    bru.io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                    committer.io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                }
                for (j<-0 until ALU_NUM)
                {
                    alus(j).io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                }
                lsu.io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                bru.io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                committer.io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                for (j<-0 until ALU_NUM)
                {
                    alus(j).io.cdbs(ALU_NUM+1):=RegNext(bru.io.out_cdb)
                }
                lsu.io.cdbs(ALU_NUM+1):=RegNext(bru.io.out_cdb)
                bru.io.cdbs(ALU_NUM+1):=RegNext(bru.io.out_cdb)
                committer.io.cdbs(ALU_NUM+1):=RegNext(bru.io.out_cdb)

                //committer
                committer.io.store_received:=false.B
                when (committer_waiting===0.U){
                    when (committer.io.out_store_valid===true.B&&exit_store_commit===false.B&&
                        Dcache_waiting===0.U&&Dcache.io.out_wvalid===false.B&&
                        lsu_waiting===0.U){//store
                        Dcache.io.store_valid := true.B
                        Dcache.io.addr := committer.io.out_store_addr(31,2)
                        Dcache.io.wdata := committer.io.out_store_value
                        Dcache.io.wmask := committer.io.out_store_mask
                        committer_waiting:=1.U
                    }.elsewhen (committer.io.out_reg_valid===true.B)
                    {
                        val id=Wire(UInt(5.W))
                        id:=committer.io.out_reg_id
                        regs(id).value := committer.io.out_reg_val
                        when (regs(id).prod.asUInt === committer.io.out_reg_prod&&issuer.io.out_prod_valid(id)===false.B){//not modified by issuer
                            regs(id).prod := (-1).S
                        }
                    }
                }.elsewhen (committer_waiting===1.U){
                    when (Dcache.io.out_hit===true.B){
                        committer_waiting:=0.U
                        committer.io.store_received:=true.B
                    }.otherwise{
                        committer_waiting:=2.U
                    }
                }.elsewhen (committer_waiting===2.U){
                    // store miss 必须先从内存取回完整 word。共享读通道由
                    // Fetch、LSU 和这里按状态串行使用，地址在握手前保持稳定。
                    when (fetcher_waiting===0.U&&lsu_waiting===0.U){
                        // AXI 读地址必须按 word 对齐；store mask 仍保留原始
                        // 字节位置，供 Cache 在写入口选择 store 数据。
                        io.araddr:=Cat(
                            committer.io.out_store_addr(31,2),
                            0.U(2.W)
                        )
                        io.arvalid:=true.B
                        when (io.arready===true.B){
                            io.rready:=true.B
                            committer_waiting:=3.U
                        }
                    }
                }.otherwise{
                    // R 数据返回的同一拍交给 Dcache 做字节合并，并确认 store。
                    // Cache 在本拍结束完成整字安装，因此后续访存不会看到旧值。
                    io.rready:=true.B
                    when (io.rvalid===true.B){
                        Dcache.io.new_valid:=true.B
                        Dcache.io.new_data:=io.rdata
                        committer_waiting:=0.U
                        committer.io.store_received:=true.B
                    }
                }

                //predictor update
                predictor.io.uvalid := committer.io.out_predictor_valid
                predictor.io.uaddr := committer.io.out_predictor_addr
                predictor.io.ures := committer.io.out_predictor_res



        }
    }.otherwise{//halt
        io.awaddr:="h80000000".U(32.W)
        io.wstrb:=15.U(4.W)
        io.wdata:=regs(10).value(7,0)
        io.wvalid:=true.B
        io.awvalid:=true.B
    }
}
