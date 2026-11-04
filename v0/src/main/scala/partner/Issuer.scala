//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Issuer
import Register._
import CDB._
import Op._
import ROB._
import RS._
import Config._
import Config.CPUConfig._
import chisel3._
import chisel3.util._
import Config.CPUConfig.issuerBufferWidth
import Config.CPUConfig.issueWidth

class Issuer extends Module{//needs to rewrite alu choosing!
  val io = IO(new Bundle {
    val in_valid = Input(Bool())
    val in_ready = Output(Bool())
    val valid_num = Input(UInt(32.W))
    val flush_valid = Input(Bool())
    val id = Input(Vec(issueWidth,UInt(32.W)))
    val cur_op = Input(Vec(issueWidth,new Op))
    val executer_space = Input(Vec(ALU_NUM+2,UInt(32.W)))
    val committer_space = Input(UInt(32.W))

    val reg= Input(Vec(32,new Register))
    val robs = Input(Vec(ROBWidth,new ROB))

    val predictor_result = Input(Vec(issueWidth,UInt(32.W)))

    val out_executer_RS = Output(Vec(issueWidth,new RS))
    val out_executer_type = Output(Vec(issueWidth, UInt(32.W)))//0 for nothing, 1-ALU_NUM for alus, ALU_NUM+1 for LSU, ALU_NUM+2 for BRU
    val out_ROB = Output(Vec(issueWidth,new ROB))
    val out_prod = Output(Vec(32,SInt(32.W)))
    val out_prod_valid = Output(Vec(32,Bool()))
    val out_valid = Output(UInt(32.W))
    val out_new_pc_val= Output(UInt(32.W))
    val out_pc_val_changed = Output(Bool())
  })
  //the buffer queue
  val clk=RegInit(1.U(32.W))
  clk:=clk+issueWidth.U(32.W)
  val op_buffer=Reg(Vec(issuerBufferWidth, new Op))
  val id_buffer=Reg(Vec(issuerBufferWidth,UInt(32.W)))
  val prods=Wire(Vec(issueWidth+1,Vec(32,SInt(32.W))))
  val spaces=Wire(Vec((ALU_NUM+2)*(issueWidth+1),UInt(32.W)))
  val should_break=Wire(Vec(issueWidth+1,Bool()))
  val out_num=Wire(Vec(issueWidth+1,UInt(32.W)))
  val l=RegInit(0.U(4.W))
  val r=RegInit(0.U(4.W))
  val startALU=RegInit(0.U(2.W))//the first alu to select
  io.out_pc_val_changed:=false.B
  io.out_new_pc_val:=0.U
  io.out_executer_RS:=0.U.asTypeOf(io.out_executer_RS)
  io.out_executer_type:=0.U.asTypeOf(io.out_executer_type)
  io.out_ROB:=0.U.asTypeOf(io.out_ROB)
  io.out_prod:=0.S.asTypeOf(io.out_prod)
  io.out_valid:=0.U

  //add into buffer
  // The ring reserves one slot to distinguish full from empty.
  val free_entries = l-r-1.U
  io.in_ready := !io.flush_valid && free_entries >= io.valid_num
  val in_fire = io.in_valid && io.in_ready
  when (in_fire){
    for(i<-0 until issueWidth){
      when (i.U<io.valid_num){
        op_buffer(r+i.U):=io.cur_op(i)
        id_buffer(r+i.U):=io.id(i)
      }
    }
    r:=r+io.valid_num
  }

  for (j<-0 until issueWidth+1){
    should_break(j):=false.B
    out_num(j):=0.U(32.W)
  }
  for (j<-0 until (ALU_NUM+2)*(issueWidth+1)){
    spaces(j):=0.U
  }
  for (i<-0 until issueWidth+1){
    for (j<-0 until 32){
      prods(i)(j):=0.S
    }
  }
  for (j<-0 until 32){
    prods(0)(j):=io.reg(j).prod
    io.out_prod_valid(j):=false.B
  }
//multi issuing
 val canOperate = Wire(Bool())
 val startingPoint = Wire(UInt(4.W))
 startingPoint:=l
 canOperate := true.B
  when (canOperate===true.B)
  {
    for (i<-0 until issueWidth){
            for (j<-0 until 32){
                prods(i+1)(j):=prods(i)(j)
            }
            for (j<-0 until ALU_NUM+2){
                spaces((i+1)*(ALU_NUM+2)+j):=spaces(i*(ALU_NUM+2)+j)
            }
            should_break(i+1):=should_break(i)
            out_num(i+1):=out_num(i)
            when (should_break(i)===false.B &&i.U < (r-startingPoint)&&i.U<io.committer_space){
                    val halt=Wire(UInt(2.W))//0 ok, 1 no space halt, 2 jump triggered
                    halt:=0.U
                    val rs=Wire(new RS)
                    val rob=Wire(new ROB)
                    // Chisel uses the last connection, so initialise the whole
                    // bundle before assigning the fields that carry meaning.
                    rs:=0.U.asTypeOf(rs)
                    // Snapshot every decoded field from the same queue entry.
                    val queuedOp = op_buffer(startingPoint+i.U)
                    rs.type_ := queuedOp.type_
                    rs.id:= clk+i.U
                    rs.q1:= (-1).S
                    rs.q2:= (-1).S
                    rs.dest:= (-1).S
                    rs.is_halt := false.B
                    rob.id:=0.U
                    rob.value:= 0.U
                    rob.dest:= 0.S
                    rob.type_ := 0.S
                    rob.ready := false.B
                    val rd=Wire(UInt(32.W))
                    val r1=Wire(UInt(32.W))
                    val r2=Wire(UInt(32.W))
                    val value=Wire(UInt(32.W))
                    val temp1=Wire(UInt(32.W))
                    val temp2=Wire(UInt(32.W))
                    val has_temp1=Wire(Bool())
                    val has_temp2=Wire(Bool())
                    val executer_type=Wire(UInt(32.W))
                    temp1:=0.U
                    temp2:=0.U
                    executer_type:=0.U
                    has_temp1:=false.B
                    has_temp2:=false.B
                    rd:=queuedOp.rd
                    r1:=queuedOp.r1
                    r2:=queuedOp.r2
                    for (j<-0 until ROBWidth){
                        when (io.robs(j).ready===true.B&&io.robs(j).id===prods(i)(r1).asUInt){
                            temp1:=io.robs(j).value
                            has_temp1:=true.B
                        }
                        when (io.robs(j).ready===true.B&&io.robs(j).id===prods(i)(r2).asUInt){
                            temp2:=io.robs(j).value
                            has_temp2:=true.B
                        }
                    }
                    value:=0.U(32.W)

                    //select alu
                    val alu_id=Wire(UInt(3.W))
                    alu_id:=ALU_NUM.U
                    for (j<-ALU_NUM-1 to 0 by -1){
                        val candidate = (j.U+startALU)(1,0)
                        for (k<-0 until ALU_NUM){
                            when (candidate===k.U){
                                when (spaces(i*(ALU_NUM+2)+k)<io.executer_space(k)){
                                    alu_id:=k.U
                                    startALU:=alu_id+1.U
                                }
                            }
                        }
                    }
                    switch (rs.type_){
                        is(OP_TYPE.OP_ADD, OP_TYPE.OP_SUB, OP_TYPE.OP_AND, OP_TYPE.OP_OR, OP_TYPE.OP_XOR,
                        OP_TYPE.OP_SLL, OP_TYPE.OP_SRL, OP_TYPE.OP_SRA, OP_TYPE.OP_SLT,OP_TYPE.OP_SLTU,
                        OP_TYPE.OP_MUL,OP_TYPE.OP_MULH,OP_TYPE.OP_MULHSU,OP_TYPE.OP_MULHU,OP_TYPE.OP_DIV,OP_TYPE.OP_DIVU,OP_TYPE.OP_REM,OP_TYPE.OP_REMU){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                when (prods(i)(r1)=== -1.S(32.W)){
                                    rs.v1:=io.reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods(i)(r1)
                                    }
                                }
                                when (prods(i)(r2)=== -1.S(32.W)){
                                    rs.v2:=io.reg(r2).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=prods(i)(r2)
                                    }
                                }
                                rs.dest:=rd.asSInt
                                prods(i+1)(rd):=(clk+i.U).asSInt
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                            }
                        }
                        is(OP_TYPE.OP_ADDI, OP_TYPE.OP_ANDI, OP_TYPE.OP_ORI, OP_TYPE.OP_XORI, OP_TYPE.OP_SLLI,
                        OP_TYPE.OP_SRLI, OP_TYPE.OP_SRAI, OP_TYPE.OP_SLTI,OP_TYPE.OP_SLTIU){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                when (prods(i)(r1)=== -1.S(32.W)){
                                    rs.v1:=io.reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods(i)(r1)
                                    }
                                }
                                rs.v2:=r2
                                rs.dest:=rd.asSInt
                                rs.is_halt:=(rs.type_ ===OP_TYPE.OP_ADDI&&r1===0.U&&r2==="hffffffff".U&&rd===10.U)//halt instruction
                                prods(i+1)(rd):=(clk+i.U).asSInt
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                            }
                        }
                        is(OP_TYPE.OP_LB, OP_TYPE.OP_LH, OP_TYPE.OP_LHU, OP_TYPE.OP_LW){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM)===io.executer_space(ALU_NUM)){
                                halt:=1.U
                            }.otherwise{
                                when (prods(i)(r1)=== -1.S(32.W)){
                                    rs.v1:=io.reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods(i)(r1)
                                    }
                                }
                                rs.v2:=r2
                                rs.dest:=rd.asSInt
                                prods(i+1)(rd):=(clk+i.U).asSInt
                                io.out_prod_valid(rd):=true.B
                                executer_type:=ALU_NUM.U//lsu
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                            }
                        }
                        is(OP_TYPE.OP_SB, OP_TYPE.OP_SH, OP_TYPE.OP_SW){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM)===io.executer_space(ALU_NUM)){
                                halt:=1.U
                            }.otherwise{
                                when (prods(i)(r1)=== -1.S(32.W)){
                                    rs.v1:=io.reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods(i)(r1)
                                    }
                                }
                                when (prods(i)(r2)=== -1.S(32.W)){
                                    rs.v2:=io.reg(r2).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=prods(i)(r2)
                                    }
                                }
                                rs.dest:=rd.asSInt
                                executer_type:=ALU_NUM.U//lsu
                                rob.id:=clk+i.U
                                rob.type_ := Mux(rs.type_ === OP_TYPE.OP_SB,1.S,Mux(rs.type_ === OP_TYPE.OP_SH,2.S,4.S))
                            }
                        }
                        is(OP_TYPE.OP_BEQ, OP_TYPE.OP_BGE, OP_TYPE.OP_BGEU,OP_TYPE.OP_BLT, OP_TYPE.OP_BLTU, OP_TYPE.OP_BNE){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM+1)===io.executer_space(ALU_NUM+1)){//bru_full
                                halt:=1.U
                            }.otherwise{
                                when (prods(i)(r1)=== -1.S(32.W)){
                                    rs.v1:=io.reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods(i)(r1)
                                    }
                                }
                                when (prods(i)(r2)=== -1.S(32.W)){
                                    rs.v2:=io.reg(r2).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=prods(i)(r2)
                                    }
                                }
                                rs.dest:=io.predictor_result(i)(0).asSInt
                                executer_type:=(ALU_NUM+1).U//bru
                                rob.id:=clk+i.U
                                rob.dest:=Mux(rs.dest=/=0.S(32.W),id_buffer(startingPoint+i.U)+4.U,(id_buffer(startingPoint+i.U).asSInt+rd.asSInt).asUInt).asSInt
                                rob.type_ := (~id_buffer(startingPoint+i.U)).asSInt
                                rob.value:=io.predictor_result(i)
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.OP_JAL){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(startingPoint+i.U)+4.U
                                rs.dest:=rd.asSInt
                                prods(i+1)(rd):=(clk+i.U).asSInt
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.OP_JALR){
                            // JALR 的目标依赖 rs1，无法在取指阶段确定，
                            // 因此由 Issue 在操作数就绪后发起一次重定向。
                            // rd == rs1 只意味着先读旧值、后写新值，不能改变
                            // rs1 的就绪条件，也不能把返回地址当作跳转基址。
                            val rs1Pending = prods(i)(r1) =/= (-1).S(32.W)
                            when (alu_id===ALU_NUM.U||(rs1Pending&&has_temp1===false.B)){//full alu or not clear rs1
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(startingPoint+i.U)+4.U
                                rs.dest:=rd.asSInt
                                prods(i+1)(rd):=(clk+i.U).asSInt
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                val rs1Value = Mux(
                                    rs1Pending,
                                    temp1,
                                    io.reg(r1).value
                                )
                                // JALR 的目标始终使用旧 rs1，并按 ISA 清除 bit 0。
                                io.out_new_pc_val:=(rs1Value.asSInt+r2.asSInt).asUInt & "hfffffffe".U
                                io.out_pc_val_changed:=true.B
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.OP_AUIPC){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(startingPoint+i.U)+r1
                                rs.dest:=rd.asSInt
                                prods(i+1)(rd):=(clk+i.U).asSInt
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                            }
                        }
                        is(OP_TYPE.OP_LUI){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=r1
                                rs.dest:=rd.asSInt
                                prods(i+1)(rd):=(clk+i.U).asSInt
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                            }
                        }
                    }
                    io.out_executer_type(i):=executer_type
                    io.out_executer_RS(i):=rs
                    io.out_ROB(i):=rob
                    when (halt=/=1.U){
                        // Use fixed indices so the staged occupancy vector is
                        // not inferred as a combinational self-cycle.
                        for (j<-0 until ALU_NUM+2){
                            when (executer_type===j.U){
                                spaces((i+1)*(ALU_NUM+2)+j):=spaces(i*(ALU_NUM+2)+j)+1.U
                            }
                        }
                        out_num(i+1):=(i+1).U
                    }
                    when (halt=/=0.U){
                        should_break(i+1):=true.B
                    }
            }
        }
        //output and update
        io.out_valid:=out_num(issueWidth)
        l:=startingPoint+out_num(issueWidth)
        for (i<-0 until 32){
            io.out_prod(i):=prods(issueWidth)(i)
        }
    }
  // Commit 侧只负责丢弃预测错误路径；下一取指 PC 由 Fetcher 管理。
  when (io.flush_valid===true.B){
    // flush 必须覆盖本周期由旧队列项产生的发射结果。
    io.out_valid:=0.U
    l:=0.U
    r:=0.U
  }
}
