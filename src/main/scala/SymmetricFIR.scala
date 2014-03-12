package SIFT

import Chisel._

object StdCoeff {
  val GaussKernel = List(UInt(6,8), UInt(58,8), UInt(128,8))
  val CenterKernel = List(UInt(0,8), UInt(0,8), UInt(255,8))
  val UnityKernel = List(UInt(1,8), UInt(1,8), UInt(1,8))
  val AvgKernel = List(UInt(85,8), UInt(85,8), UInt(85,8))
}

/*  delay: delay between individual elements
  * line: number of elements in a line
  * n_tap: number of taps in a filter
  * dwidth: bit width of elements
  * coeff: coefficients of symmetric FIR filter, listed from outer to center
  */
class SymmetricFIR(delay: Int, line: Int, n_tap: Int, dwidth : Int = 8,
  coeff: List[UInt] = StdCoeff.UnityKernel) extends Module{
  val io = new Bundle {
    val in = Decoupled(UInt(width = dwidth)).flip
    val out = Decoupled(UInt(width = dwidth))
  }

  // Delay from shifters to adder input
  val mul_delay = 1

  // Delay from adder tree. Determines number of retiming registers
  val sum_delay = 1

  // Count (zero index - 1) of middle tap (number of pathways)
  val mid_tap = (n_tap+1)/2

  // Delay from first input to terms arriving at adder
  val term_delay = (mid_tap-1) * delay + mul_delay
  
  // Delay from first element in to first element out
  val total_delay = term_delay + sum_delay
  
  println(
    "delay:" + delay + 
    ", term_delay:" + term_delay + 
    ", total:" + total_delay)

  //val advance = Mux(state === s_prime, io.in.valid, io.out.ready)
  val advance = Bool()

  // Count inputs until pipeline is primed
  val in_counter = Module(new Counter(UInt(delay*line-1)))
  in_counter.io.en := io.in.fire()

  // Counter for line to disable terms when at edges
  val term_en = Reg(init = Bool(false))
  
  val term_counter = Module(new Counter(UInt(delay*line-1)))
  term_counter.io.en := term_en & advance
  
  when((in_counter.io.count === UInt(term_delay-1)) & advance) {
    term_en := Bool(true)
  } .elsewhen (term_counter.io.top & advance) {
    term_en := Bool(false)
  }

  // Count outputs
  val out_counter = Module(new Counter(UInt(delay*line-1)))
  out_counter.io.en := io.out.fire()

  // State machine governing pipeline behavior
  val s_prime :: s_pipe :: s_flush :: Nil = Enum(UInt(),3)
  
  val state = Reg(init = s_prime)

  // Default to s_prime behavior
  io.in.ready := Bool(true)
  io.out.valid := Bool(false)
  advance := io.in.valid

  switch (state) {
    is (s_prime) {
      io.in.ready := Bool(true)
      io.out.valid := Bool(false)
      advance := io.in.valid

      when ((in_counter.io.count === UInt (total_delay-1)) & advance) {
        state := s_pipe
      }}
    is (s_pipe) {
      io.in.ready := io.out.ready
      io.out.valid := io.in.valid
      advance := io.in.valid & io.out.ready
      
      when (out_counter.io.top & advance) {
        state := s_prime
      } .elsewhen (in_counter.io.top & advance) {
        state := s_flush
      }}
    is (s_flush) {
      io.in.ready := Bool(true)
      io.out.valid := Bool(true)
      advance := io.out.ready

      when (out_counter.io.top & advance) {
        state := s_prime
      } .elsewhen (io.in.valid) {
        state := s_pipe
      }}
  }
  
  /*def tap_delays[T <: Data](x: T, n: Int): List[T] = {
    if(n <= 1) 
      List(x) 
    else 
      x :: tap_delays(ShiftRegister(x, delay, advance), n-1)
  }*/
  
  //val taps = tap_delays(io.in.bits, mid_tap)

  val mul_in = Vec.fill(mid_tap) {UInt(width = dwidth)}
  mul_in(0) := io.in.bits

  for (i <- 1 until mid_tap) {
    //mul_in(i) := taps(i)
    mul_in(i) := ShiftRegister(mul_in(i-1), delay, advance)
  }

  // Element-wise multiplication of coeff and delay elements
  val mul_out = (coeff,mul_in).zipped.map( _ * _ )
  
  // Add multiplier retiming registers
  val mul_out_d = Vec.fill(mid_tap) {UInt(width = 2*dwidth)}
  for (i <- 0 until mid_tap) {
    mul_out_d(i) := ShiftRegister(mul_out(i), mul_delay, advance)
  } 

  // Collect all terms to sum
  val terms = Vec.fill(n_tap) { UInt(width=2*dwidth) }
  terms(mid_tap-1) := mul_out_d(mid_tap-1)

  for (tap_idx <- 0 until mid_tap - 1) {
    // Low-side muxes
    terms(tap_idx) := Mux(
      term_counter.io.count > UInt(tap_idx*delay), 
      mul_out_d(tap_idx),
      UInt(0))

    // High-side muxes and delays
    terms(n_tap-tap_idx-1) := Mux(
      term_counter.io.count < UInt((line-tap_idx-1)*delay),
      ShiftRegister(
        mul_out_d(tap_idx),
        (n_tap-(2*tap_idx)-1)*delay,
        advance),
      UInt(0))
  }

  val sum = terms.reduceRight( _ + _ )
  val sum_d = ShiftRegister(sum(15,8), sum_delay, advance) 
  io.out.bits := sum_d
}
