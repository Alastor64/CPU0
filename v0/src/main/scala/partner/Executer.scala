//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused
package Executer

import Op._
import RS._
import CDB._
import ROB._
import Config._
import Config.CPUConfig._
import _root_.MemoryLane.MemoryLane
import chisel3._
import chisel3.util._
class Adder extends Module {
  val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val sum = Output(UInt(32.W))
  })
  val p= Wire(Vec(63,Bool()))
  val g= Wire(Vec(63,Bool()))
  val c= Wire(Vec(32,Bool()))
  for (i<-0 until 32){
    p(i):=io.a(i)^io.b(i)
    g(i):=io.a(i)&io.b(i)
  }
  var offset1=32
  var offset2=0
  for (w<-4 to 0 by -1){
    for (i<-0 until (1<<w)){
      p(i+offset1):=p(i*2+offset2)&p(i*2+offset2+1)
      g(i+offset1):=g(i*2+offset2+1)|(p(i*2+offset2+1)&g(i*2+offset2))
    }
    offset2=offset1
    offset1+=(1<<w)

  }
  var offset=62
  c(0):=false.B
  for (w<-4 to 0 by -1){
    for (i<-(32-(1<<(w+1))) to 0 by -(1<<(w+1))){
      offset-=2
      c(i+(1<<w)):=g(offset)|(p(offset)&c(i))
    }
  }
  val pBits = p.asUInt
  val cBits = c.asUInt
  io.sum := pBits(31, 0) ^ cBits  
}

class LShifter extends Module{
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(5.W))
    val c = Output(UInt(32.W))
  })
  val a1,a2,a3,a4=Wire(UInt(32.W))
  a1:=Mux(io.b(0),Cat(io.a(30,0),0.U(1.W)),io.a)
  a2:=Mux(io.b(1),Cat(a1(29,0),0.U(2.W)),a1)
  a3:=Mux(io.b(2),Cat(a2(27,0),0.U(4.W)),a2)
  a4:=Mux(io.b(3),Cat(a3(23,0),0.U(8.W)),a3)
  io.c:=Mux(io.b(4),Cat(a4(15,0),0.U(16.W)),a4)
}

class RShifter extends Module{
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(5.W))
    val signed = Input(Bool())
    val c = Output(UInt(32.W))
  })
  val a1,a2,a3,a4=Wire(UInt(32.W))
  when (io.signed&io.a(31))
  {
        a1:=Mux(io.b(0),Cat(1.U(1.W),io.a(31,1)),io.a)
        a2:=Mux(io.b(1),Cat(3.U(2.W),a1(31,2)),a1)
        a3:=Mux(io.b(2),Cat(15.U(4.W),a2(31,4)),a2)
        a4:=Mux(io.b(3),Cat(255.U(8.W),a3(31,8)),a3)
        io.c:=Mux(io.b(4),Cat(65535.U(15.W),a4(31,16)),a4)
  }.otherwise{
        a1:=Mux(io.b(0),Cat(0.U(1.W),io.a(31,1)),io.a)
        a2:=Mux(io.b(1),Cat(0.U(2.W),a1(31,2)),a1)
        a3:=Mux(io.b(2),Cat(0.U(4.W),a2(31,4)),a2)
        a4:=Mux(io.b(3),Cat(0.U(8.W),a3(31,8)),a3)
        io.c:=Mux(io.b(4),Cat(0.U(15.W),a4(31,16)),a4)
  }
}

class Subtracter extends Module{
  val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val c = Output(UInt(32.W))
  })
  val p= Wire(Vec(63,Bool()))
  val g= Wire(Vec(63,Bool()))
  val c= Wire(Vec(32,Bool()))
  for (i<-0 until 32){
    p(i):=io.a(i)^(!io.b(i))
    g(i):=io.a(i)&(!io.b(i))
  }
  var offset1=32
  var offset2=0
  for (w<-4 to 0 by -1){
    for (i<-0 until (1<<w)){
      p(i+offset1):=p(i*2+offset2)&p(i*2+offset2+1)
      g(i+offset1):=g(i*2+offset2+1)|(p(i*2+offset2+1)&g(i*2+offset2))
    }
    offset2=offset1
    offset1+=(1<<w)

  }
  var offset=62
  c(0):=true.B
  for (w<-4 to 0 by -1){
    for (i<-(32-(1<<(w+1))) to 0 by -(1<<(w+1))){
      offset-=2
      c(i+(1<<w)):=g(offset)|(p(offset)&c(i))
    }
  }
  val pBits = p.asUInt
  val cBits = c.asUInt
  io.c := pBits(31, 0) ^ cBits  
}


class Comparer extends Module{//returns 1 if a>=b
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val signed = Input(Bool())
    val c = Output(Bool())
  })
  val eq = (io.a===io.b)
  val reverted = io.signed&io.a(31)
  val sub=Module(new Subtracter)
  sub.io.a:=io.a
  sub.io.b:=io.b
  io.c:=((!sub.io.c(31))^reverted)|eq
}

class Mult extends Module{
    val io = IO(new Bundle {
    val valid = Input(UInt(32.W))
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val op = Input(Bool())//select lower 32 bits or higher 32 bits, 0 for low and 1 for high

    val clear_valid = Input(Bool())
    val ready = Output(UInt(32.W))
    val c = Output(UInt(32.W))
    val received = Output(UInt(32.W))
    })
    val aL=io.a(15,0)
    val aH=io.a(31,16)
    val bL=io.b(15,0)
    val bH=io.b(31,16)
    val p00 = RegNext(aL * bL)
    val p01 = RegNext(aL * bH)
    val p10 = RegNext(aH * bL)
    val p11 = RegNext(aH * bH)
    val adder1=Module(new Adder)
    val adder2=Module(new Adder)
    val adder3=Module(new Adder)
    val adder4=Module(new Adder)

    val received = RegInit(0.U(32.W))
    val ready1 = RegInit(0.U(32.W))
    val ready2 = RegInit(0.U(32.W))
    adder1.io.a:=p00
    adder1.io.b:=Cat(p01(15,0),0.U(16.W))
    adder3.io.a:=p11
    adder3.io.b:=Cat(0.U(16.W),p01(31,16))

    adder2.io.a:=RegNext(adder1.io.sum)
    adder2.io.b:=Cat(RegNext(p10(15,0)),0.U(16.W))
    adder4.io.a:=RegNext(adder3.io.sum)
    adder4.io.b:=Cat(0.U(16.W),RegNext(p10(31,16)))

    // op=0 选择低 32 位，op=1 选择高 32 位。
    io.c:=Mux(RegNext(RegNext(io.op)),adder4.io.sum,adder2.io.sum)
    io.ready:=0.U
    io.received:=0.U
    when (io.clear_valid)
    {
      received:=0.U
      ready1:=0.U
      ready2:=0.U
    }.otherwise
    {
      received:=io.valid
      io.received:=received
      ready1:=io.valid
      ready2:=ready1
      io.ready:=ready2
  }
}


class Divider extends Module{//unsigned
  val io = IO(new Bundle {
    val valid = Input(UInt(32.W))
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val op = Input(Bool())//select quotient or remainder, 1 for quotient and 0 for remainder

    val clear_valid = Input(Bool())

    val ready = Output(UInt(32.W))//which alu
    val c = Output(UInt(32.W))
    val received = Output(UInt(32.W))
    })
    // Stage i consumes the current MSB at the next step, then carries only the
    // remaining lower bits. Narrowing the pipeline avoids 32 full-width copies
    // of the dividend.
    val A = Seq.tabulate(31)(i => Reg(UInt((31 - i).W)))
    val B = Reg(Vec(32, UInt(32.W)))
    val R = Reg(Vec(31, UInt(31.W)))
    val Q = Seq.tabulate(31)(i => Reg(UInt((i+1).W)))
    val finalRemainder = Reg(UInt(32.W))
    val finalQuotient = Reg(UInt(32.W))
    val rd =RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
    val ops= Reg(Vec(32,Bool()))
    val r= Wire(Vec(32,UInt(32.W)))
    val subs = Seq.fill(32)(Module(new Subtracter))
    val received = RegInit(0.U(32.W))
    received:=io.valid
    for (i<-0 until 32){
       if (i==0){
        r(i):=Cat(0.U(31.W),io.a(31-i))
        A(i):=io.a(30,0)
        B(i):=io.b
        rd(i):=io.valid
        ops(i):=io.op
       }
       else{
        r(i):=Cat(R(i-1)(30,0),A(i-1)(31-i))
        if (i<31){
          A(i):=A(i-1)(30-i,0)
        }
        B(i):=B(i-1)
        rd(i):=rd(i-1)
        ops(i):=ops(i-1)
       }
        subs(i).io.a:=r(i)
        if (i==0){
          subs(i).io.b:=io.b
        }
        else{
          subs(i).io.b:=B(i-1)
        }
       val quotientBit = !subs(i).io.c(31)
       if (i<31){
        R(i):=Mux(subs(i).io.c(31),r(i)(30,0),subs(i).io.c(30,0))
        if (i==0){
          Q(i):=quotientBit.asUInt
        }
        else{
          Q(i):=Cat(Q(i-1),quotientBit)
        }
       }
       else{
        finalRemainder:=Mux(subs(i).io.c(31),r(i),subs(i).io.c)
        finalQuotient:=Cat(Q(i-1),quotientBit)
       }
    }
    when (io.clear_valid===true.B){
        received:=0.U
        for (i<-0 until 32){
          rd(i):=0.U
        }
    }
    io.ready:=rd(31)
    io.c:=Mux(ops(31),finalQuotient,finalRemainder)
    io.received:=received
}



class Executer(val rsDepth: Int = RSWidth) extends Module{
  val io = IO(new Bundle {
    val valid_num = Input(UInt(32.W))
    val rs = Input(Vec(issueWidth,new RS))
    val cdbs = Input(Vec(ALU_NUM+2,new CDB))

    val clear_valid = Input(Bool())

    val out_cdb = Output(new CDB)
    val out_space = Output(UInt(32.W))
    val in_ready = Output(Bool())
  })

  private val rsIndexBits = log2Ceil(rsDepth)
  private val rsCountBits = log2Ceil(rsDepth + 1)
  private val idWidth = 32

  /**
    * 在有限数量未完成指令的窗口中，按 32 位回绕差值判断发射年龄。
    * 不能直接使用无符号 `<`，否则跨过 0xffffffff 时会把新指令误判为旧指令。
    *
    * 这里只计算减法的最高位，而不是生成完整的 `a - b` 后再取 `[31]`。
    * 减法第 31 位等于 `a31 ^ b31 ^ borrow31`，其中 borrow31 由低 31 位
    * 的 unsigned 比较给出。这样既保持回绕语义，也避免综合器为每一次年龄
    * 比较保留 32 位减法结果和 31 位无用逻辑。
    */
  private def isOlder(a: UInt, b: UInt): Bool = {
    val borrowIntoMsb = a(idWidth - 2, 0) < b(idWidth - 2, 0)
    a(idWidth - 1) ^ b(idWidth - 1) ^ borrowIntoMsb
  }

  // 空闲槽位由 valid bitmap 表示，不再通过整项搬运维持连续队列。
  val rss = Reg(Vec(rsDepth, new RS()))
  val valid = RegInit(VecInit(Seq.fill(rsDepth)(false.B)))
  val freeCount = RegInit(rsDepth.U(rsCountBits.W))
  val cur_rs = Reg(new RS)
  val in_process=RegInit(false.B)
  val temp_rs=Wire(new RS)
  val init = Wire(new CDB)
  temp_rs:=0.U.asTypeOf(temp_rs)
  init.id :=0.U
  init.dest :=0.S
  init.value :=0.U
  val out_cdb= RegInit(init)

  io.in_ready := !io.clear_valid && freeCount >= io.valid_num
  io.out_space := freeCount
  io.out_cdb:=out_cdb
  val start_process=Wire(Bool())//for telling subclasses to execute rs
  start_process:=false.B

  when (!io.clear_valid) {
    // Issuer 必须保证整组可接收；不允许静默截断，否则 RS、ROB 与 Issuer
    // 会对同一条指令产生不同的接收结果。
    assert(
      freeCount >= io.valid_num,
      "Executer received more reservation-station entries than free slots"
    )
  }

  // 本周期插入的项也要吸收同拍 CDB，否则生产者结果到达的同拍新入队项
  // 会永久保留过期 q tag。
  val incomingStages =
    Wire(Vec(issueWidth, Vec(ALU_NUM+3, new RS)))
  for (i <- 0 until issueWidth) {
    incomingStages(i)(0) := io.rs(i)
    for (j <- 0 until ALU_NUM+2) {
      incomingStages(i)(j+1) := incomingStages(i)(j)
      when (incomingStages(i)(j).q1 === io.cdbs(j).id.asSInt) {
        incomingStages(i)(j+1).q1 := (-1).S
        incomingStages(i)(j+1).v1 := io.cdbs(j).value
      }
      when (incomingStages(i)(j).q2 === io.cdbs(j).id.asSInt) {
        incomingStages(i)(j+1).q2 := (-1).S
        incomingStages(i)(j+1).v2 := io.cdbs(j).value
      }
    }
  }

  // 从 bitmap 中为本拍最多 issueWidth 项依次选择空槽。后一项会看到
  // 前一项已经占用的位置，避免同一槽位在一拍内被分配两次。
  val freeStage = Wire(Vec(issueWidth+1, Vec(rsDepth, Bool())))
  for (i <- 0 until rsDepth) {
    freeStage(0)(i) := !valid(i)
  }
  val allocatedSlot = Wire(Vec(issueWidth, UInt(rsIndexBits.W)))
  for (i <- 0 until issueWidth) {
    allocatedSlot(i) := PriorityEncoder(freeStage(i))
    for (j <- 0 until rsDepth) {
      freeStage(i+1)(j) := freeStage(i)(j) && allocatedSlot(i) =/= j.U
    }
  }

  when (io.clear_valid===true.B){//flush
    // Committer flush 到达时，比触发指令更老的项已经按序提交完成。
    // 因此保留站可以直接恢复为全空，不需要逐项拼接空闲链表。
    for (i <- 0 until rsDepth) {
      valid(i) := false.B
    }
    freeCount := rsDepth.U
    in_process:=false.B
  }.otherwise{
    for (i<-0 until ALU_NUM+2){//receive CDB
      for (j<-0 until rsDepth)
      {
        when (valid(j))
        {
            when (rss(j).q1===io.cdbs(i).id.asSInt)
            {
                rss(j).q1:=(-1).S
                rss(j).v1:=io.cdbs(i).value
            }
            when (rss(j).q2===io.cdbs(i).id.asSInt)
            {
                rss(j).q2:=(-1).S
                rss(j).v2:=io.cdbs(i).value
            }
        }
      }
    }

    // 只分配周期开始时已经空闲的槽位。本拍弹出的槽位下一拍才可复用，
    // 使 ready 判断与 Issuer 当前基于 out_space 的容量规划保持一致。
    for (i<-0 until issueWidth){//receiving RS
      when (i.U<io.valid_num){
        rss(allocatedSlot(i)):=incomingStages(i)(ALU_NUM+2)
        valid(allocatedSlot(i)):=true.B
      }
    }

    // 从所有就绪项中选择发射序号最老的一项。load 还必须等待所有更老
    // store 的地址和数据就绪，并检查地址区间是否重叠。
    val candidateReady = Wire(Vec(rsDepth, Bool()))
    for (i <- 0 until rsDepth) {
      val rs = rss(i)
      val loadLen = Mux(
        rs.type_ === OP_TYPE.OP_LW, 4.U(32.W),
        Mux(
          rs.type_ === OP_TYPE.OP_LH || rs.type_ === OP_TYPE.OP_LHU,
          2.U(32.W),
          Mux(
            rs.type_ === OP_TYPE.OP_LB || rs.type_ === OP_TYPE.OP_LBU,
            1.U(32.W),
            0.U(32.W)
          )
        )
      )
      val loadLeft = (rs.v1.asSInt + rs.v2.asSInt).asUInt
      val loadRight = loadLeft + loadLen
      val olderStoreStages = Wire(Vec(rsDepth+1, Bool()))
      olderStoreStages(0) := false.B
      when (loadLen =/= 0.U) {
        for (j <- 0 until rsDepth) {
          val older = rss(j)
          val storeBytes = Mux(
            older.type_ === OP_TYPE.OP_SB, 1.U(32.W),
            Mux(
              older.type_ === OP_TYPE.OP_SH, 2.U(32.W),
              Mux(older.type_ === OP_TYPE.OP_SW, 4.U(32.W), 0.U(32.W))
            )
          )
          val storeLeft = (older.v1.asSInt + older.dest).asUInt
          val storeRight = storeLeft + storeBytes
          olderStoreStages(j+1) := olderStoreStages(j)
          when (
            valid(j) && isOlder(older.id, rs.id) && storeBytes =/= 0.U &&
              (older.q1 =/= (-1).S || (storeLeft < loadRight && loadLeft < storeRight))
          ) {
            olderStoreStages(j+1) := true.B
          }
        }
      }.otherwise {
        for (j <- 0 until rsDepth) {
          olderStoreStages(j+1) := false.B
        }
      }
      candidateReady(i) :=
        valid(i) && rs.q1 === (-1).S && rs.q2 === (-1).S &&
          !olderStoreStages(rsDepth)
    }

    val bestFound = Wire(Vec(rsDepth+1, Bool()))
    val bestAge = Wire(Vec(rsDepth+1, UInt(idWidth.W)))
    val bestSlot = Wire(Vec(rsDepth+1, UInt(rsIndexBits.W)))
    bestFound(0) := false.B
    bestAge(0) := 0.U
    bestSlot(0) := 0.U
    for (i <- 0 until rsDepth) {
      bestFound(i+1) := bestFound(i)
      bestAge(i+1) := bestAge(i)
      bestSlot(i+1) := bestSlot(i)
      when (candidateReady(i)) {
        when (!bestFound(i) || isOlder(rss(i).id, bestAge(i))) {
          bestFound(i+1) := true.B
          bestAge(i+1) := rss(i).id
          bestSlot(i+1) := i.U
        }
      }
    }

    val selected = !in_process && bestFound(rsDepth)
    when (selected) {
      temp_rs := rss(bestSlot(rsDepth))
      cur_rs := rss(bestSlot(rsDepth))
      valid(bestSlot(rsDepth)) := false.B
      start_process := true.B
    }

    freeCount := freeCount - io.valid_num + Mux(selected, 1.U, 0.U)
  }
}

class ALU(val id: Int, rsDepth: Int = RSWidth) extends Executer(rsDepth) {
  val aluIO= IO(new Bundle {
    val mult_valid = Input(UInt(32.W))
    val mult_res = Input(UInt(32.W))
    val mult_received = Input(UInt(32.W))
    
    val div_valid = Input(UInt(32.W))
    val div_res = Input(UInt(32.W))
    val div_received = Input(UInt(32.W))

    val out_mult_a = Output(UInt(32.W))
    val out_mult_b = Output(UInt(32.W))
    val out_mult_op = Output(Bool())
    val out_mult_valid = Output(Bool())

    val out_div_a = Output(UInt(32.W))
    val out_div_b = Output(UInt(32.W))
    val out_div_op = Output(Bool())
    val out_div_valid = Output(Bool())
  })
  aluIO.out_mult_valid:=false.B
  aluIO.out_div_valid:=false.B
  aluIO.out_mult_a:=0.U
  aluIO.out_mult_b:=0.U
  aluIO.out_mult_op:=false.B
  aluIO.out_div_a:=0.U
  aluIO.out_div_b:=0.U
  aluIO.out_div_op:=false.B

  /** 32 位模意义下的动态左移，避免 Chisel 生成 63 位后只截取低 32 位。 */
  private def shiftLeft32(value: UInt, amount: UInt): UInt =
    (0 until 5).foldLeft(value) { (shifted, stage) =>
      val distance = 1 << stage
      Mux(
        amount(stage),
        Cat(shifted(31-distance,0),0.U(distance.W)),
        shifted,
      )
    }

  val temp_out_mult_a=Reg(UInt(32.W))
  val temp_out_mult_b=Reg(UInt(32.W))
  val temp_out_mult_op=Reg(Bool())
  val temp_out_mult_valid=RegInit(false.B)
  val temp_out_div_a=Reg(UInt(32.W))
  val temp_out_div_b=Reg(UInt(32.W))
  val temp_out_div_op=Reg(Bool())
  val temp_out_div_valid=RegInit(false.B)

  val res= Wire(UInt(32.W))
  res:=0.U
  val isMulDiv=Wire(Bool())
  isMulDiv:=false.B
  val waiting=RegInit(false.B)
  val sgn = RegInit(false.B)//for mul and div
  when (io.clear_valid===true.B){
    waiting:=false.B
  }.otherwise{
  when (in_process===true.B)
  {
      when (waiting===true.B)
      {
        when (aluIO.mult_received===(id+1).U||aluIO.div_received===(id+1).U){
            waiting:=false.B
          }.otherwise{//keep sending
            aluIO.out_mult_a:=temp_out_mult_a
            aluIO.out_mult_b:=temp_out_mult_b
            aluIO.out_mult_op:=temp_out_mult_op
            aluIO.out_mult_valid:=temp_out_mult_valid
            aluIO.out_div_a:=temp_out_div_a
            aluIO.out_div_b:=temp_out_div_b
            aluIO.out_div_op:=temp_out_div_op
            aluIO.out_div_valid:=temp_out_div_valid
          }
      }.otherwise{//other operations
        when (aluIO.mult_valid===(id+1).U)
        {
          io.out_cdb.id:=cur_rs.id
          io.out_cdb.dest:=cur_rs.dest
          io.out_cdb.value:=Mux(sgn,~aluIO.mult_res+1.U,aluIO.mult_res)
          in_process:=false.B
        }.elsewhen (aluIO.div_valid===(id+1).U)
        {
          io.out_cdb.id:=cur_rs.id
          io.out_cdb.dest:=cur_rs.dest
          io.out_cdb.value:=Mux(sgn,~aluIO.div_res+1.U,aluIO.div_res)
          in_process:=false.B
        }
    }
  }
  when (start_process===true.B){
    switch (temp_rs.type_){
      is(OP_TYPE.OP_ADD){
        res:=temp_rs.v1+temp_rs.v2
      }
      is(OP_TYPE.OP_SUB){
        res:=temp_rs.v1-temp_rs.v2
      }
      is (OP_TYPE.OP_AND){
        res:=temp_rs.v1&temp_rs.v2
      }
      is (OP_TYPE.OP_OR){
        res:=temp_rs.v1|temp_rs.v2
      }
      is (OP_TYPE.OP_XOR){
        res:=temp_rs.v1^temp_rs.v2
      }
      is (OP_TYPE.OP_SLL){
        res:=shiftLeft32(temp_rs.v1,temp_rs.v2(4,0))
      }
      is (OP_TYPE.OP_SRL){
        res:=(temp_rs.v1>>(temp_rs.v2(4,0)))
      }
      is (OP_TYPE.OP_SRA){
        res:=(temp_rs.v1.asSInt>>(temp_rs.v2(4,0))).asUInt
      }
      is (OP_TYPE.OP_SLT){
        res:=Mux(temp_rs.v1.asSInt<temp_rs.v2.asSInt,1.U,0.U)
      }
      is (OP_TYPE.OP_SLTU){
        res:=Mux(temp_rs.v1<temp_rs.v2,1.U,0.U)
      }
      is (OP_TYPE.OP_ADDI){
        res:=(temp_rs.v1.asSInt+temp_rs.v2.asSInt).asUInt
      }
      is (OP_TYPE.OP_ANDI){
        res:=temp_rs.v1 & temp_rs.v2
      }
      is (OP_TYPE.OP_ORI){
        res:=temp_rs.v1 | temp_rs.v2
      }
      is (OP_TYPE.OP_XORI){
        res:=temp_rs.v1 ^ temp_rs.v2
      }
      is (OP_TYPE.OP_SLLI){
        res:=shiftLeft32(temp_rs.v1,temp_rs.v2(4,0))
      }
      is (OP_TYPE.OP_SRLI){
        res:=temp_rs.v1>>temp_rs.v2(4,0)
      }
      is (OP_TYPE.OP_SRAI){
        res:=(temp_rs.v1.asSInt>>temp_rs.v2(4,0)).asUInt
      }
      is (OP_TYPE.OP_SLTI){
        res:=Mux(temp_rs.v1.asSInt<temp_rs.v2.asSInt,1.U,0.U)
      }
      is (OP_TYPE.OP_SLTIU){
        res:=Mux(temp_rs.v1<temp_rs.v2,1.U,0.U)
      }
      is (OP_TYPE.OP_JAL,OP_TYPE.OP_JALR,OP_TYPE.OP_AUIPC,OP_TYPE.OP_LUI){
        res:=temp_rs.v1
      }
      is (OP_TYPE.OP_MUL){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := false.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_MULHU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_MULH){
        sgn:=temp_rs.v1(31)^temp_rs.v2(31)
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := Mux(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_MULHSU){
        sgn:=temp_rs.v1(31)
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_DIVU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := true.B}
        Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_DIV){
        when (temp_rs.v1==="h80000000".U&&temp_rs.v2==="hffffffff".U){
          res:="h80000000".U
        }.otherwise{
          sgn:=temp_rs.v1(31)^temp_rs.v2(31)
          waiting:=true.B
          isMulDiv:=true.B
          Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
          Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := Mux(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
          Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := true.B}
          Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
        }
      }
      is (OP_TYPE.OP_REMU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := false.B}
        Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_REM){
        when (temp_rs.v1==="h80000000".U&&temp_rs.v2==="hffffffff".U){//special case
          res:=0.U
        }.otherwise{
          sgn:=temp_rs.v1(31)
          waiting:=true.B
          isMulDiv:=true.B
          Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
          Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := Mux(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
          Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := false.B}
          Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
        }
      }
    }
    when (isMulDiv===true.B){
      in_process:=true.B
    }.otherwise{
        io.out_cdb.id:=temp_rs.id
        io.out_cdb.dest:=temp_rs.dest
        io.out_cdb.value:=res
    }
  }
  }
}

class LSU(rsDepth: Int = RSWidth) extends Executer(rsDepth) {
  val lsuIO= IO(new Bundle {
    val robs = Input(Vec(ROBWidth,new ROB))
    val l = Input(UInt(4.W))
    val r = Input(UInt(4.W))

    val addr_valid = Input(Bool())
    val addr_received = Input(Bool())
    val addr_value = Input(UInt(32.W))
    val out_valid = Output(Bool())
    val out_addr = Output(UInt(32.W))
  })
  val res=Wire(UInt(32.W))
  val wanted=Reg(UInt(32.W))
  val waiting=RegInit(false.B)//0 already received, 1 wait for memory
  val canOutput= Wire(Bool())
  canOutput:=true.B
  lsuIO.out_valid:=false.B
  lsuIO.out_addr:=0.U
  res:=0.U

  when (io.clear_valid===true.B){
    waiting:=false.B
  }.otherwise{
  when (in_process===true.B){
      when (waiting===true.B)
      {
        // Keep the request asserted until the response arrives. Suppressing
        // it with addr_received would feed the parent's acknowledgement
        // (which itself depends on out_valid) directly back into out_valid.
        lsuIO.out_valid:=true.B
        lsuIO.out_addr:=wanted
        canOutput:=false.B
        when (lsuIO.addr_received===true.B)
        {
          waiting:=false.B
        }
      }
      when (canOutput===true.B&&lsuIO.addr_valid===true.B){
        io.out_cdb.id:=cur_rs.id
        val laneWord = MemoryLane.selectLoadWord(lsuIO.addr_value, wanted)
        switch (cur_rs.type_)
        {
          is (OP_TYPE.OP_LB){
            io.out_cdb.value:=Cat(Fill(24, laneWord(7)), laneWord(7,0))
          }
          is (OP_TYPE.OP_LBU){
            io.out_cdb.value:=Cat(0.U(24.W), laneWord(7,0))
          }
          is (OP_TYPE.OP_LH){
            io.out_cdb.value:=Cat(Fill(16, laneWord(15)), laneWord(15,0))
          }
          is (OP_TYPE.OP_LHU){
            io.out_cdb.value:=Cat(0.U(16.W), laneWord(15,0))
          }
          is (OP_TYPE.OP_LW){
            io.out_cdb.value:=laneWord
          }
        }
        io.out_cdb.dest:=cur_rs.dest
        in_process:=false.B
      }
  }
    
  val len=Wire(UInt(32.W))
  val L=Wire(UInt(32.W))
  val R=Wire(UInt(32.W))
  len:=0.U
  L:=0.U
  R:=0.U
  val vals=Wire(Vec(4,UInt(9.W)))
  for (i<-0 until 4){vals(i):=0x100.U}
  when (start_process===true.B){
    len := Mux(temp_rs.type_ === OP_TYPE.OP_LW, 4.U, Mux(temp_rs.type_ === OP_TYPE.OP_LH || temp_rs.type_ === OP_TYPE.OP_LHU, 2.U, Mux(temp_rs.type_ === OP_TYPE.OP_LB || temp_rs.type_ === OP_TYPE.OP_LBU, 1.U, 0.U)))
    when (len=/=0.U){//load
      L:=(temp_rs.v1.asSInt+temp_rs.v2.asSInt).asUInt
      R:=L+len
      for (i<-0 until ROBWidth){
        when (i.U<(lsuIO.r-lsuIO.l)){
          val rb=Wire(new ROB)
          rb:=lsuIO.robs(lsuIO.l+i.U)
          when (rb.id < temp_rs.id && rb.type_ > 0.S && rb.dest.asUInt < R && rb.dest.asUInt+rb.type_.asUInt > L){
            for (j<-0 until 4){
              val offset = Wire(UInt(32.W))
              offset:=rb.dest.asUInt+j.U-L
              when (j.S < rb.type_ && offset.asSInt >= 0.S && offset< len){
                vals(offset):=rb.value(8*j+7,8*j)
              }
            }
          }
        }
      }
      val flag=Wire(Bool())
      flag:=false.B
      for (i<-0 until 4)
      {
          when (i.U < len && vals(i)===0x100.U)
          {
            lsuIO.out_addr:=L
            lsuIO.out_valid:=true.B
            wanted:=L
            waiting:=true.B
            in_process:=true.B
            flag:=true.B
          }
      }
      when (flag===false.B){
        switch (temp_rs.type_)
        {
          is (OP_TYPE.OP_LB){
            res:=Cat(Fill(24, vals(0)(7)), vals(0)(7,0))
          }
          is (OP_TYPE.OP_LBU){
            res:=vals(0)(7,0)
          }
          is (OP_TYPE.OP_LH){
            res:=Cat(Fill(16, vals(1)(7)), vals(1)(7,0),vals(0)(7,0))
          }
          is (OP_TYPE.OP_LHU){
            res:=Cat(vals(1)(7,0),vals(0)(7,0))
          }
          is (OP_TYPE.OP_LW){
            res:=Cat(vals(3)(7,0),vals(2)(7,0),vals(1)(7,0),vals(0)(7,0))
          }
        }
        io.out_cdb.id:=temp_rs.id
        io.out_cdb.value:=res
        io.out_cdb.dest:=temp_rs.dest
      }
    }.otherwise{//store
      io.out_cdb.id:=temp_rs.id
      io.out_cdb.value:=temp_rs.v2
      io.out_cdb.dest:=temp_rs.v1.asSInt+temp_rs.dest
    }
  }
  }
}


class BRU(rsDepth: Int = RSWidth) extends Executer(rsDepth) {
  val res=Wire(Bool())
  res:=false.B
  when (io.clear_valid===false.B)
  {
    when (start_process===true.B){
      switch (temp_rs.type_){
        is (OP_TYPE.OP_BEQ){
          res:=(temp_rs.v1===temp_rs.v2)^temp_rs.branchInvert
        }
        is (OP_TYPE.OP_BGE){
          res:=(temp_rs.v1.asSInt>=temp_rs.v2.asSInt)^temp_rs.branchInvert
        }
        is (OP_TYPE.OP_BGEU){
          res:=(temp_rs.v1>=temp_rs.v2)^temp_rs.branchInvert
        }
        is (OP_TYPE.OP_BLT){
          res:=(temp_rs.v1.asSInt<temp_rs.v2.asSInt)^temp_rs.branchInvert
        }
        is (OP_TYPE.OP_BLTU){
          res:=(temp_rs.v1<temp_rs.v2)^temp_rs.branchInvert
        }
        is (OP_TYPE.OP_BNE){
          res:=(temp_rs.v1=/=temp_rs.v2)^temp_rs.branchInvert
        }
      }
      io.out_cdb.id:=temp_rs.id
      io.out_cdb.dest:=(-2).S
      io.out_cdb.value:=res
    }
  }
}
