//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Decoder
import Op._
import Config._
import Config.CPUConfig._
import chisel3._
import chisel3.util._
import Config.CPUConfig.issueWidth


class Decoder extends Module{
    val io=IO(new Bundle{
        val in_valid = Input(Bool())
        val in_ready = Output(Bool())
        val valid_num = Input(UInt(32.W))
        val id = Input(Vec(issueWidth, UInt(32.W)))
        val opcode = Input(Vec(issueWidth, UInt(32.W)))

        val flush_valid = Input(Bool())
        val out_valid = Output(Bool())
        val out_ready = Input(Bool())
        val out_num = Output(UInt(32.W))
        val out_id = Output(Vec(issueWidth, UInt(32.W)))
        val out_op = Output(Vec(issueWidth, new Op))
    })
    //init
    val occupied = RegInit(false.B)
    val valid_num_reg=RegInit(0.U(32.W))
    val opcode_reg=Reg(Vec(issueWidth,UInt(32.W)))
    val id_reg=Reg(Vec(issueWidth,UInt(32.W)))
    // All signed immediates leave the decoder as complete 32-bit two's
    // complement values. Consumers can therefore use the field directly.
    def signExtend(value: UInt): UInt =
        value.asSInt.pad(32).asUInt

    def immediateI(instruction: UInt): UInt =
        signExtend(instruction(31,20))

    def immediateS(instruction: UInt): UInt =
        signExtend(Cat(instruction(31,25), instruction(11,7)))

    def immediateB(instruction: UInt): UInt =
        signExtend(Cat(
            instruction(31),
            instruction(7),
            instruction(30,25),
            instruction(11,8),
            0.U(1.W),
        ))

    def immediateJ(instruction: UInt): UInt =
        signExtend(Cat(
            instruction(31),
            instruction(19,12),
            instruction(20),
            instruction(30,21),
            0.U(1.W),
        ))
    // One-entry elastic buffer between the fetcher and the issuer queue.
    io.in_ready := !io.flush_valid && (!occupied || io.out_ready)
    io.out_valid := occupied && !io.flush_valid
    val in_fire = io.in_valid && io.in_ready
    val out_fire = io.out_valid && io.out_ready
    when (io.flush_valid) {
        occupied := false.B
        valid_num_reg := 0.U
    }.otherwise {
        // Draining first lets the same edge replace the old payload.
        when (out_fire) {
            occupied := false.B
        }
        when (in_fire) {
            valid_num_reg := io.valid_num
            opcode_reg := io.opcode
            id_reg := io.id
            occupied := true.B
        }
    }

    //decode
    io.out_num:=valid_num_reg
    for (i<-0 until issueWidth){
        io.out_id(i):=id_reg(i)
        io.out_op(i).id:=0.U(32.W)
        // Invalid instructions and unsupported opcodes must still produce a
        // deterministic, inert decode result.
        io.out_op(i).rd:=0.U(32.W)
        io.out_op(i).r1:=0.U(32.W)
        io.out_op(i).r2:=0.U(32.W)
        io.out_op(i).type_ := OP_TYPE.OP_NONE
        switch (opcode_reg(i)(6,0)){
            is("b0110011".U){//R
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(24,20)
                switch(opcode_reg(i)(25,25))
                {
                    is("b0".U){
                        switch(opcode_reg(i)(14,12)){
                            is("b000".U){
                                io.out_op(i).type_ := Mux(opcode_reg(i)(30),OP_TYPE.OP_SUB,OP_TYPE.OP_ADD)
                            }
                            is("b111".U){
                                io.out_op(i).type_ := OP_TYPE.OP_AND
                            }
                            is ("b110".U){
                                io.out_op(i).type_ := OP_TYPE.OP_OR
                            }
                            is ("b100".U){
                                io.out_op(i).type_ := OP_TYPE.OP_XOR
                            }
                            is ("b001".U){
                                io.out_op(i).type_ := OP_TYPE.OP_SLL
                            }
                            is ("b101".U){
                                io.out_op(i).type_ := Mux(opcode_reg(i)(30),OP_TYPE.OP_SRA,OP_TYPE.OP_SRL)
                            }
                            is ("b010".U){
                                io.out_op(i).type_ := OP_TYPE.OP_SLT
                            }
                            is ("b011".U){
                                io.out_op(i).type_ := OP_TYPE.OP_SLTU
                            }
                        }
                    }
                    is("b1".U){//M extension
                        switch(opcode_reg(i)(14,12)){
                            is ("b000".U){//low 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MUL
                            }
                            is ("b001".U){//signed*signed, high 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MULH
                            }
                            is ("b010".U){//signed*unsigned, high 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MULHSU
                            }
                            is ("b011".U){//unsigned*unsigned, high 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MULHU
                            }
                            is ("b100".U){//signed a/b, round to 0
                                io.out_op(i).type_ := OP_TYPE.OP_DIV
                            }
                            is ("b101".U){
                                io.out_op(i).type_ := OP_TYPE.OP_DIVU
                            }
                            is ("b110".U){//same sign with a
                                io.out_op(i).type_ := OP_TYPE.OP_REM
                            }
                            is ("b111".U){
                                io.out_op(i).type_ := OP_TYPE.OP_REMU
                            }
                        }
                    }
                }
            }
            is("b0010011".U){//I(*)
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=immediateI(opcode_reg(i))
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_ADDI
                    }
                    is ("b111".U){
                        io.out_op(i).type_ := OP_TYPE.OP_ANDI
                    }
                    is ("b110".U){
                        io.out_op(i).type_ := OP_TYPE.OP_ORI
                    }
                    is ("b100".U){
                        io.out_op(i).type_ := OP_TYPE.OP_XORI
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SLLI
                    }
                    is ("b101".U){
                        io.out_op(i).type_ := Mux(opcode_reg(i)(30),OP_TYPE.OP_SRAI,OP_TYPE.OP_SRLI)
                    }
                    is ("b010".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SLTI
                    }
                    is ("b011".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SLTIU
                    }
                }
            }
            is("b0000011".U){//I memory
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=immediateI(opcode_reg(i))
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LB
                    }
                    is ("b100".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LBU
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LH
                    }
                    is ("b101".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LHU
                    }
                    is ("b010".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LW
                    }
                }
            }
            is("b0100011".U){//S
                io.out_op(i).rd:=immediateS(opcode_reg(i))
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(24,20)
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SB
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SH
                    }
                    is ("b010".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SW
                    }
                }
            }
            is("b1100011".U){//B
                io.out_op(i).rd:=immediateB(opcode_reg(i))
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(24,20)
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BEQ
                    }
                    is ("b101".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BGE
                    }
                    is ("b111".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BGEU
                    }
                    is ("b100".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BLT
                    }
                    is ("b110".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BLTU
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BNE
                    }
                }
            }
            is("b1101111".U){//JAL
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=immediateJ(opcode_reg(i))
                io.out_op(i).r2:=0.U(32.W)
                io.out_op(i).type_ := OP_TYPE.OP_JAL
            }
            is ("b1100111".U){//JALR
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=immediateI(opcode_reg(i))
                io.out_op(i).type_ := OP_TYPE.OP_JALR
            }
            is("b0010111".U){//AUIPC
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=Cat(opcode_reg(i)(31,12),0.U(12.W))
                io.out_op(i).r2:=0.U(32.W)
                io.out_op(i).type_ := OP_TYPE.OP_AUIPC
            }
            is("b0110111".U){//LUI
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=Cat(opcode_reg(i)(31,12),0.U(12.W))
                io.out_op(i).r2:=0.U(32.W)
                io.out_op(i).type_ := OP_TYPE.OP_LUI
            }
        }
    }
}
