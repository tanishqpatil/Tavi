/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Adapted for Tavi: full-image resize only; no video capture or processing.
package app.lfm3.probe.inference.litert

internal object Bicubic {
  fun resizeBicubic(src: ByteArray, w: Int, h: Int, ow: Int, oh: Int): ByteArray {
    val horizontalFirst = !((h - oh) > 0 && (h - oh) > (w - ow) * 2)
    var cur = src
    var cw = w
    var ch = h
    if (horizontalFirst) {
      if (ow != cw) cur = passHorizontal(cur, cw, ch, ow).also { cw = ow }
      if (oh != ch) cur = passVertical(cur, cw, ch, oh).also { ch = oh }
    } else {
      if (oh != ch) cur = passVertical(cur, cw, ch, oh).also { ch = oh }
      if (ow != cw) cur = passHorizontal(cur, cw, ch, ow).also { cw = ow }
    }
    return if (cur === src) src.copyOf() else cur
  }

  /** One horizontal pass: [w] x [h] -> [ow] x [h]. */
  private fun passHorizontal(src: ByteArray, w: Int, h: Int, ow: Int): ByteArray {
    val (bounds, kk, ksize) = coeffs(w, ow)
    val out = ByteArray(ow * h * 3)
    for (y in 0 until h) {
      val row = y * w * 3
      val orow = y * ow * 3
      for (xx in 0 until ow) {
        val xmin = bounds[2 * xx]
        val xmax = bounds[2 * xx + 1]
        val k = xx * ksize
        var s0 = HALF
        var s1 = HALF
        var s2 = HALF
        for (x in 0 until xmax) {
          val p = row + (x + xmin) * 3
          val c = kk[k + x]
          s0 += (src[p].toInt() and 0xff) * c
          s1 += (src[p + 1].toInt() and 0xff) * c
          s2 += (src[p + 2].toInt() and 0xff) * c
        }
        out[orow + xx * 3] = clip8(s0)
        out[orow + xx * 3 + 1] = clip8(s1)
        out[orow + xx * 3 + 2] = clip8(s2)
      }
    }
    return out
  }

  /** One vertical pass: [w] x [h] -> [w] x [oh]. */
  private fun passVertical(src: ByteArray, w: Int, h: Int, oh: Int): ByteArray {
    val (bounds, kk, ksize) = coeffs(h, oh)
    val out = ByteArray(w * oh * 3)
    for (yy in 0 until oh) {
      val ymin = bounds[2 * yy]
      val ymax = bounds[2 * yy + 1]
      val k = yy * ksize
      val orow = yy * w * 3
      for (xx in 0 until w) {
        var s0 = HALF
        var s1 = HALF
        var s2 = HALF
        for (y in 0 until ymax) {
          val p = ((y + ymin) * w + xx) * 3
          val c = kk[k + y]
          s0 += (src[p].toInt() and 0xff) * c
          s1 += (src[p + 1].toInt() and 0xff) * c
          s2 += (src[p + 2].toInt() and 0xff) * c
        }
        out[orow + xx * 3] = clip8(s0)
        out[orow + xx * 3 + 1] = clip8(s1)
        out[orow + xx * 3 + 2] = clip8(s2)
      }
    }
    return out
  }

  private const val PRECISION_BITS = 32 - 8 - 2
  private const val HALF = 1 shl (PRECISION_BITS - 1)

  private fun clip8(v: Int): Byte {
    val x = v shr PRECISION_BITS
    return (if (x < 0) 0 else if (x > 255) 255 else x).toByte()
  }

  private fun bicubic(v: Double): Double {
    val a = -0.5
    val x = if (v < 0.0) -v else v
    if (x < 1.0) return ((a + 2.0) * x - (a + 3.0)) * x * x + 1
    if (x < 2.0) return (((x - 5) * x + 8) * x - 4) * a
    return 0.0
  }

  /** Pillow's precompute_coeffs + normalize_coeffs_8bpc for [inSize] -> [outSize]: (xmin, count) per output, the
   *  fixed-point weights ([ksize] per output). */
  private fun coeffs(inSize: Int, outSize: Int): Triple<IntArray, IntArray, Int> {
    val scale = inSize.toDouble() / outSize
    val filterscale = if (scale < 1.0) 1.0 else scale
    val support = 2.0 * filterscale
    val ksize = Math.ceil(support).toInt() * 2 + 1
    val bounds = IntArray(outSize * 2)
    val kk = IntArray(outSize * ksize)
    val k = DoubleArray(ksize)
    val ss = 1.0 / filterscale
    for (xx in 0 until outSize) {
      val center = (xx + 0.5) * scale
      var ww = 0.0
      var xmin = (center - support + 0.5).toInt()
      if (xmin < 0) xmin = 0
      var xmax = (center + support + 0.5).toInt()
      if (xmax > inSize) xmax = inSize
      xmax -= xmin
      for (x in 0 until xmax) {
        val wgt = bicubic(((x + xmin) - center + 0.5) * ss)
        k[x] = wgt
        ww += wgt
      }
      for (x in 0 until xmax) if (ww != 0.0) k[x] /= ww
      for (x in 0 until ksize) {
        val v = if (x < xmax) k[x] else 0.0
        kk[xx * ksize + x] = if (v < 0) (-0.5 + v * (1 shl PRECISION_BITS)).toInt() else (0.5 + v * (1 shl PRECISION_BITS)).toInt()
      }
      bounds[2 * xx] = xmin
      bounds[2 * xx + 1] = xmax
    }
    return Triple(bounds, kk, ksize)
  }

}
