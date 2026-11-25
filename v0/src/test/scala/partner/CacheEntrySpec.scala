package partner

import _root_.Cache.CacheEntry
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 验证 I/D Cache 的 tag、valid、dirty 打包格式。 */
class CacheEntrySpec extends AnyFlatSpec with Matchers {

  private class EntryHarness(tagBits: Int, hasDirty: Boolean) extends Module {
    val io = IO(new Bundle {
      val tag = Input(UInt(tagBits.W))
      val valid = Input(Bool())
      val dirty = Input(Bool())
      val packed = Output(UInt(CacheEntry.width(tagBits, hasDirty).W))
      val tagOut = Output(UInt(tagBits.W))
      val validOut = Output(Bool())
      val dirtyOut = Output(Bool())
    })

    io.packed :=
      CacheEntry.pack(io.tag, io.valid, io.dirty, hasDirty)
    io.tagOut := CacheEntry.tag(io.packed, tagBits, hasDirty)
    io.validOut := CacheEntry.valid(io.packed)
    io.dirtyOut := CacheEntry.dirty(io.packed, hasDirty)
  }

  "CacheEntry" should "pack only valid with an I-Cache tag" in {
    simulate(new EntryHarness(tagBits = 21, hasDirty = false)) { dut =>
      val tag = BigInt("155aaa", 16)
      dut.io.tag.poke(tag.U)
      dut.io.valid.poke(true.B)
      dut.io.dirty.poke(true.B)

      dut.io.packed.expect(((tag << 1) | 1).U)
      dut.io.tagOut.expect(tag.U)
      dut.io.validOut.expect(true.B)
      dut.io.dirtyOut.expect(false.B)
    }
  }

  it should "pack dirty and valid with a D-Cache tag" in {
    simulate(new EntryHarness(tagBits = 23, hasDirty = true)) { dut =>
      val tag = BigInt("7abcd", 16)
      dut.io.tag.poke(tag.U)
      dut.io.valid.poke(true.B)
      dut.io.dirty.poke(true.B)

      dut.io.packed.expect(((tag << 2) | 3).U)
      dut.io.tagOut.expect(tag.U)
      dut.io.validOut.expect(true.B)
      dut.io.dirtyOut.expect(true.B)
    }
  }

  it should "distinguish clean and dirty entries" in {
    simulate(new EntryHarness(tagBits = 23, hasDirty = true)) { dut =>
      val tag = BigInt("12345", 16)
      dut.io.tag.poke(tag.U)
      dut.io.valid.poke(true.B)
      dut.io.dirty.poke(false.B)

      dut.io.packed.expect(((tag << 2) | 1).U)
      dut.io.dirtyOut.expect(false.B)
    }
  }
}
