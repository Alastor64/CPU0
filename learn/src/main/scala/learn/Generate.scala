package learn

import chisel3._
// _root_ 前缀是为了和 chisel3.util.circt 下的同名类区分开
import _root_.circt.stage.ChiselStage

/** 把 learn 里的示例模块导出成 SystemVerilog。
  *
  * 用法（在仓库根目录）：
  *   scripts/gen.sh learn learn.Generate learn/build/generated
  *
  * 每个模块单独导出一个文件，文件名就是模块名。
  */
object Generate extends App {
  val outDir = args.headOption.getOrElse("build/generated")

  // 这套选项和助教框架的 Chisel 模板保持一致，作用是：
  //   - 关掉随机初始化、去掉调试信息，让生成的 Verilog 干净、可复现；
  //   - 把 verification layer 就地展开（否则会额外生成若干
  //     layers-*-Verification.sv 包含文件，单独编译其中一个文件会失败）；
  //   - 断言用 if-else-fatal 形式表达，且不用局部变量、不用打包数组，
  //     这样 Verilator 与 Yosys 都能直接吃下生成的代码。
  val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable",
    "-verification-flavor=if-else-fatal",
    "-lowering-options=disallowLocalVariables,disallowPackedArrays",
  )

  // 新增例子时，只要在这里登记一行（名字 -> 构造模块的代码）
  val designs: Seq[(String, () => Module)] = Seq(
    "counter_w8"   -> (() => new Counter(8)),
    "alu_w8"       -> (() => new Alu(8)),
    "vecsum_n4_w8" -> (() => new VecSum(4, 8)),
  )

  new java.io.File(outDir).mkdirs()
  for ((name, build) <- designs) {
    val sv = ChiselStage.emitSystemVerilog(gen = build(), firtoolOpts = firtoolOpts)
    val file = new java.io.File(outDir, s"$name.sv")
    java.nio.file.Files.write(file.toPath, sv.getBytes("UTF-8"))
    println(s"[gen] $file")
  }
}
