`timescale 1ns/1ps

// 验证课程 sram_fakeram 的吞吐和时序。
//
// 该 SRAM 是单端口 1RW：每个周期只能有一个读或一个写。它可以连续周期
// 发起访问，但不存在同一周期并行读写的双端口能力。
module tb_sram_pipeline;
    localparam integer DEPTH  = 1024;
    localparam integer WIDTH  = 32;
    localparam integer WMASK_BITS = 4;
    localparam integer ADDR_BITS  = 10;

    reg clk = 1'b0;
    always #5 clk = ~clk;

    reg                       en = 1'b0;
    reg                       we = 1'b0;
    reg [WMASK_BITS-1:0]  wmask = '0;
    reg [ADDR_BITS-1:0]    addr = '0;
    reg [WIDTH-1:0]       wdata = '0;
    wire [WIDTH-1:0]      rdata;

    integer errors = 0;
    integer i;
    reg [WIDTH-1:0] observed;
    reg [WIDTH-1:0] expected;

    sram_fakeram #(
        .DEPTH(DEPTH),
        .WIDTH(WIDTH),
        .WRITE_GRANULARITY(8)
    ) dut (
        .clk(clk),
        .en(en),
        .we(we),
        .wmask(wmask),
        .addr(addr),
        .wdata(wdata),
        .rdata(rdata)
    );

    // 在下降沿设置信号，使上升沿成为唯一采样时刻，避免测试台竞争。
    // 每个任务恰好推进一个时钟周期，因此连续调用就是逐拍背靠背访问。
    task automatic write_word(
        input [ADDR_BITS-1:0] access_addr,
        input [WIDTH-1:0] access_data,
        input [WMASK_BITS-1:0] access_mask
    );
        begin
            @(negedge clk);
            en    = 1'b1;
            we    = 1'b1;
            addr  = access_addr;
            wdata = access_data;
            wmask = access_mask;
            @(posedge clk);
            #1;
            en    = 1'b0;
            we    = 1'b0;
            wmask = '0;
        end
    endtask

    task automatic read_word(
        input [ADDR_BITS-1:0] access_addr,
        output [WIDTH-1:0] read_data
    );
        begin
            @(negedge clk);
            en    = 1'b1;
            we    = 1'b0;
            addr  = access_addr;
            wmask = '0;
            @(posedge clk);
            #1;
            read_data = rdata;
            en        = 1'b0;
        end
    endtask

    task automatic check_word(
        input [ADDR_BITS-1:0] check_addr,
        input [WIDTH-1:0] check_data,
        input [8*80-1:0] label
    );
        begin
            read_word(check_addr, observed);
            if (observed !== check_data) begin
                $display("FAIL %0s: addr=%0d expected=%08h actual=%08h",
                         label, check_addr, check_data, observed);
                errors = errors + 1;
            end else begin
                $display("PASS %0s: addr=%0d data=%08h", label, check_addr, observed);
            end
        end
    endtask

    initial begin
        // 1. 连续四个周期写入，验证写吞吐为一个字/周期。
        for (i = 0; i < 4; i = i + 1)
            write_word(i[ADDR_BITS-1:0], 32'h1000_0000 | i, 4'hf);
        $display("PASS back-to-back write burst");

        // 2. 连续四个周期读。首字有一拍延迟，之后每个周期都输出一个新字。
        for (i = 0; i < 4; i = i + 1) begin
            expected = 32'h1000_0000 | i;
            read_word(i[ADDR_BITS-1:0], observed);
            if (observed !== expected) begin
                $display("FAIL pipelined read: addr=%0d expected=%08h actual=%08h",
                         i, expected, observed);
                errors = errors + 1;
            end
        end
        if (errors == 0)
            $display("PASS one-cycle-latency pipelined read burst");

        // 3. 写之后紧接下一周期读，验证中间不需要插入空周期。
        write_word(10'd4, 32'hcafe_f00d, 4'hf);
        check_word(10'd4, 32'hcafe_f00d, "write then read");

        // 4. 字节写掩码只更新第 0、2 字节。
        write_word(10'd5, 32'habcd_ef01, 4'hf);
        write_word(10'd5, 32'h5566_7788, 4'b0101);
        check_word(10'd5, 32'hab66_ef88, "byte write mask");

        if (errors != 0) begin
            $display("SRAM pipeline test FAILED: %0d error(s)", errors);
            $fatal(1);
        end
        $display("SRAM pipeline test PASSED");
        $finish;
    end
endmodule
