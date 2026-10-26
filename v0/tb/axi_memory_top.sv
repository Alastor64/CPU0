`timescale 1ns/1ps
/* verilator lint_off DECLFILENAME */

// AXI4-Lite 外部内存吞吐测试。
//
// 这个模块只用于测试，不是 CPU 本体。它依次执行：
//   1. 连续 64 个周期发出写地址和写数据；
//   2. 连续 64 个周期发出读地址，并检查按序返回的数据；
//   3. 同时连续发出 64 笔读和 64 笔写，检查全双工流水能力；
//   4. 向退出 MMIO 写成功码 0x2026。
//
// 框架的 sim.cpp 最多每周期分别启动一个读和一个写，AR/AW/W/R/B
// FIFO 深度均为 16，默认响应延迟为 10 周期。
module student_top (
    input  wire        clock,
    input  wire        reset,
    output reg  [31:0] araddr,
    output reg         arvalid,
    input  wire        arready,
    input  wire [31:0] rdata,
    input  wire [1:0]  rresp,
    input  wire        rvalid,
    output reg         rready,
    output reg  [31:0] awaddr,
    output reg         awvalid,
    input  wire        awready,
    output reg  [31:0] wdata,
    output reg  [3:0]  wstrb,
    output reg         wvalid,
    input  wire        wready,
    input  wire [1:0]  bresp,
    input  wire        bvalid,
    output reg         bready
);
    localparam [7:0]   BURST     = 8'd64;
    localparam [31:0]  DATA_BASE = 32'h0000_0100;
    localparam [31:0]  EXIT_ADDR = 32'h8000_0000;
    localparam [31:0]  SUCCESS   = 32'h0000_2026;

    localparam [3:0] ST_WRITE       = 4'd0;
    localparam [3:0] ST_WAIT_B      = 4'd1;
    localparam [3:0] ST_READ        = 4'd2;
    localparam [3:0] ST_WAIT_R      = 4'd3;
    localparam [3:0] ST_DUPLEX      = 4'd4;
    localparam [3:0] ST_WAIT_DUPLEX = 4'd5;
    localparam [3:0] ST_EXIT_SEND   = 4'd6;
    localparam [3:0] ST_EXIT_WAIT   = 4'd7;
    localparam [3:0] ST_DONE        = 4'd8;
    localparam [3:0] ST_FAILED      = 4'd9;

    reg [3:0]  state;
    reg [7:0]  wr_sent;
    reg [7:0]  b_recv;
    reg [7:0]  rd_sent;
    reg [7:0]  r_recv;
    reg [7:0]  dup_rd_sent;
    reg [7:0]  dup_wr_sent;
    reg [7:0]  dup_r_recv;
    reg [7:0]  dup_b_recv;
    reg [31:0] error_code;

    wire ar_fire = arvalid && arready;
    wire aw_fire = awvalid && awready;
    wire  w_fire =  wvalid &&  wready;
    wire  r_fire =  rvalid &&  rready;
    wire  b_fire =  bvalid &&  bready;
    wire [7:0] r_fire_count = r_fire ? 8'd1 : 8'd0;
    wire [7:0] b_fire_count = b_fire ? 8'd1 : 8'd0;

    function automatic [31:0] written_word(input [7:0] index);
        written_word = 32'ha500_0000 | {24'b0, index};
    endfunction

    // 各状态使用的 AXI 输出。AW 和 W 始终同时发送，方便测试从机的
    // 独立通道队列是否能够在每周期同时接收地址和数据。
    always @* begin
        araddr  = 32'b0;
        arvalid = 1'b0;
        rready  = (state != ST_DONE);
        awaddr  = 32'b0;
        awvalid = 1'b0;
        wdata   = 32'b0;
        wstrb   = 4'b0;
        wvalid  = 1'b0;
        bready  = (state != ST_DONE);

        case (state)
            ST_WRITE: begin
                awaddr  = DATA_BASE + {{22'b0}, wr_sent, 2'b00};
                awvalid = 1'b1;
                wdata   = written_word(wr_sent);
                wstrb   = 4'hf;
                wvalid  = 1'b1;
            end
            ST_READ: begin
                araddr  = DATA_BASE + {{22'b0}, rd_sent, 2'b00};
                arvalid = 1'b1;
            end
            ST_DUPLEX: begin
                araddr  = DATA_BASE + {{22'b0}, dup_rd_sent, 2'b00};
                arvalid = 1'b1;
                awaddr  = DATA_BASE + 32'h400 + {{22'b0}, dup_wr_sent, 2'b00};
                awvalid = 1'b1;
                wdata   = 32'hd00d_0000 | {24'b0, dup_wr_sent};
                wstrb   = 4'hf;
                wvalid  = 1'b1;
            end
            ST_EXIT_SEND: begin
                awaddr  = EXIT_ADDR;
                awvalid = 1'b1;
                wdata   = (error_code == 32'b0) ? SUCCESS : error_code;
                wstrb   = 4'hf;
                wvalid  = 1'b1;
            end
            default: begin
            end
        endcase
    end

    always @(posedge clock) begin
        if (reset) begin
            state       <= ST_WRITE;
            wr_sent     <= 8'b0;
            b_recv      <= 8'b0;
            rd_sent     <= 8'b0;
            r_recv      <= 8'b0;
            dup_rd_sent <= 8'b0;
            dup_wr_sent <= 8'b0;
            dup_r_recv  <= 8'b0;
            dup_b_recv  <= 8'b0;
            error_code  <= 32'b0;
        end else begin
            // 先更新响应计数，再处理当前状态转换，避免漏掉发送阶段的响应。
            if (r_fire && (state == ST_READ || state == ST_WAIT_R)) begin
                if (rresp != 2'b00 || rdata != written_word(r_recv)) begin
                    $display("FAIL read burst: index=%0d resp=%b data=%08h expected=%08h",
                             r_recv, rresp, rdata, written_word(r_recv));
                    error_code <= 32'h0000_1001;
                end
                r_recv <= r_recv + 1'b1;
            end

            if (r_fire && (state == ST_DUPLEX || state == ST_WAIT_DUPLEX)) begin
                if (rresp != 2'b00 || rdata != written_word(dup_r_recv)) begin
                    $display("FAIL duplex read: index=%0d resp=%b data=%08h expected=%08h",
                             dup_r_recv, rresp, rdata, written_word(dup_r_recv));
                    error_code <= 32'h0000_1002;
                end
                dup_r_recv <= dup_r_recv + 1'b1;
            end

            if (b_fire && (state == ST_WRITE || state == ST_WAIT_B)) begin
                if (bresp != 2'b00) begin
                    $display("FAIL write burst response: resp=%b", bresp);
                    error_code <= 32'h0000_2001;
                end
                b_recv <= b_recv + 1'b1;
            end

            if (b_fire && (state == ST_DUPLEX || state == ST_WAIT_DUPLEX)) begin
                if (bresp != 2'b00) begin
                    $display("FAIL duplex write response: resp=%b", bresp);
                    error_code <= 32'h0000_2002;
                end
                dup_b_recv <= dup_b_recv + 1'b1;
            end

            case (state)
                ST_WRITE: begin
                    if (!awready || !wready) begin
                        $display("FAIL write burst stalled: awready=%b wready=%b",
                                 awready, wready);
                        error_code <= 32'h0000_2003;
                        state <= ST_EXIT_SEND;
                    end else begin
                        wr_sent <= wr_sent + 1'b1;
                        if (wr_sent == BURST - 1)
                            state <= ST_WAIT_B;
                    end
                end
                ST_WAIT_B: begin
                    if (b_recv + b_fire_count == BURST) begin
                        rd_sent <= 8'b0;
                        r_recv  <= 8'b0;
                        state   <= ST_READ;
                    end
                end
                ST_READ: begin
                    if (!arready) begin
                        $display("FAIL read burst stalled");
                        error_code <= 32'h0000_1003;
                        state <= ST_EXIT_SEND;
                    end else begin
                        rd_sent <= rd_sent + 1'b1;
                        if (rd_sent == BURST - 1)
                            state <= ST_WAIT_R;
                    end
                end
                ST_WAIT_R: begin
                    if (r_recv + r_fire_count == BURST) begin
                        dup_rd_sent <= 8'b0;
                        dup_wr_sent <= 8'b0;
                        dup_r_recv  <= 8'b0;
                        dup_b_recv  <= 8'b0;
                        state       <= ST_DUPLEX;
                    end
                end
                ST_DUPLEX: begin
                    if (!arready || !awready || !wready) begin
                        $display("FAIL duplex channels stalled: arready=%b awready=%b wready=%b",
                                 arready, awready, wready);
                        error_code <= 32'h0000_3001;
                        state <= ST_EXIT_SEND;
                    end else begin
                        if (ar_fire)
                            dup_rd_sent <= dup_rd_sent + 1'b1;
                        if (aw_fire && w_fire)
                            dup_wr_sent <= dup_wr_sent + 1'b1;
                        if (dup_rd_sent == BURST - 1 &&
                            dup_wr_sent == BURST - 1) begin
                            state <= ST_WAIT_DUPLEX;
                        end
                    end
                end
                ST_WAIT_DUPLEX: begin
                    if (dup_r_recv + r_fire_count == BURST &&
                        dup_b_recv + b_fire_count == BURST) begin
                        state <= ST_EXIT_SEND;
                    end
                end
                ST_EXIT_SEND: begin
                    if (aw_fire && w_fire)
                        state <= ST_EXIT_WAIT;
                end
                ST_EXIT_WAIT: begin
                    if (b_fire) begin
                        if (bresp != 2'b00) begin
                            error_code <= 32'h0000_4001;
                            $display("FAIL exit response: resp=%b", bresp);
                        end else if (error_code == 32'b0) begin
                            $display("PASS AXI4-Lite memory pipeline: 64 writes + 64 reads + full-duplex");
                        end
                        state <= ST_DONE;
                    end
                end
                ST_FAILED: begin
                    $display("AXI memory test stopped after error %08h", error_code);
                end
                ST_DONE: begin
                end
                default: begin
                    error_code <= 32'h0000_ffff;
                    state <= ST_EXIT_SEND;
                end
            endcase

            if (state == ST_WAIT_DUPLEX &&
                dup_r_recv + r_fire_count == BURST &&
                dup_b_recv + b_fire_count == BURST &&
                error_code != 32'b0)
                $display("AXI memory errors detected, code=%08h", error_code);
        end
    end
endmodule
