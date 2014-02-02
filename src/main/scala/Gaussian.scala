package SIFT

import Chisel._

class Gaussian(it: ImageType, n_tap: Int) extends Module{
  val io = new Bundle{
    val reset = Bool(INPUT)
    val in = Valid(UInt(width=it.dwidth)).asInput
    val out = Valid(UInt(width=it.dwidth)).asOutput
  }

  val row_fir = Module(new SymmetricFIR(1, it.width, n_tap))
  row_fir.io.reset := reset
  row_fir.io.in <> io.in

  val col_fir = Module(new SymmetricFIR(it.width, it.height, n_tap))
  col_fir.io.reset := reset
  col_fir.io.in <> row_fir.io.out
  io.out <> row_fir.io.out
}
