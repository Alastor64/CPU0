package cpu

import chisel3._
// _root_ 前缀是为了和 chisel3.util.circt 下的同名类区分开
import _root_.circt.stage.ChiselStage

/** 把 CPU 顶层导出成 SystemVerilog，交给助教框架编译与评测。
  *
  * 用法（在仓库根目录）：
  *   scripts/gen.sh v0 cpu.Generate v0/build/generated
  * 或者直接用发布脚本（生成 + 复制到提交仓库 + 维护 filelist）：
  *   scripts/publish.sh
  *
  * firtool 选项与助教框架的 Chisel 模板保持一致，这几项都有实际原因：
  *   - 关掉随机初始化：否则同一份源码每次生成的波形/网表都不同，面积与时序无法复现；
  *   - 展开 verification layer：否则会额外生成 layers-*.sv 包含文件，
  *     框架的 filelist 只列一个文件，编译时会找不到 include；
  *   - 断言用 if-else-fatal、不用局部变量与打包数组：保证 Verilator 与 Yosys 都能直接处理。
  */
object Generate extends App {
  val outDir = args.headOption.getOrElse("build/generated")

  val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable",
    "-verification-flavor=if-else-fatal",
    "-lowering-options=disallowLocalVariables,disallowPackedArrays",
  )

  new java.io.File(outDir).mkdirs()
  val sv = ChiselStage.emitSystemVerilog(gen = new StudentTop, firtoolOpts = firtoolOpts)
  val file = new java.io.File(outDir, "student_top.sv")
  java.nio.file.Files.write(file.toPath, sv.getBytes("UTF-8"))
  println(s"[gen] $file")
}
