// counter_w8 的仿真测试台（用 Verilator 跑）。
//
// 运行:
//   scripts/sim.sh tb_counter learn/build/sim_counter \
//       learn/build/generated/counter_w8.sv learn/tb/tb_counter.sv
//
// 写法约定：激励只在时钟下降沿改变，检查放在上升沿之后。
// 同一个时刻既改输入又采样会产生事件竞争，不同仿真器结果可能不一样。
`timescale 1ns/1ps

module tb_counter;
  logic       clock = 0;
  logic       reset = 1;
  logic       io_en = 0;
  logic [7:0] io_q;

  // Chisel 的 IO Bundle 会展开成 io_* 端口
  counter_w8 dut(
    .clock(clock),
    .reset(reset),
    .io_en(io_en),
    .io_q  (io_q)
  );

  // 100 MHz 时钟
  always #5 clock = ~clock;

  initial begin
    // 复位保持若干周期
    repeat (4) @(negedge clock);
    reset = 0;
    repeat (2) @(negedge clock);

    // 使能后等 5 个上升沿，计数应该走到 5
    io_en = 1;
    repeat (5) @(negedge clock);
    #1;
    if (io_q !== 8'd5) begin
      $display("FAIL: 期望计数 5，实际得到 %0d", io_q);
      $fatal(1);
    end
    $display("PASS: 计数到 %0d", io_q);

    // 关掉使能，再等 2 个上升沿，寄存器应该保持不变
    io_en = 0;
    repeat (2) @(negedge clock);
    #1;
    if (io_q !== 8'd5) begin
      $display("FAIL: 使能为 0 时计数仍在变化，实际 %0d", io_q);
      $fatal(1);
    end
    $display("PASS: 使能为 0 时保持 %0d", io_q);
    $finish;
  end
endmodule
