/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.ring

import kotlinx.benchmark.*
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val BENCHMARK_SIZE = 100_000
private const val INT_EXPONENT_COUNT = 31 // Unbiased exponents -1 through 29.
private const val LONG_EXPONENT_COUNT = 64 // Unbiased exponents -1 through 62.

@State(Scope.Benchmark)
@Measurement(time = 100, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
class FloatingPointRoundingBenchmark {
    private val doubleValues = DoubleArray(BENCHMARK_SIZE) { index ->
        val sign = if ((index % 2) == 0) 0L else Long.MIN_VALUE
        // Visit every normal Double exponent once per 2046 indices.
        val exponent = (1 + index * 73 % 2046).toLong() shl 52
        // An odd multiplier gives distinct 52-bit fractions for the first 2^52 indices.
        val fraction = (index.toLong() * 0x9E37_79B9_7F4A_7C15uL.toLong()) and 0x000f_ffff_ffff_ffffL
        Double.fromBits(sign or exponent or fraction)
    }
    private val doubleToIntValues = generateDoubleValues(INT_EXPONENT_COUNT)
    private val doubleToLongValues = generateDoubleValues(LONG_EXPONENT_COUNT)
    private val floatValues = FloatArray(BENCHMARK_SIZE) { index ->
        val sign = if ((index % 2) == 0) 0 else Int.MIN_VALUE
        // generate an exponent in 1..254, hitting each possible value once per each 254 indices
        val exponent = (1 + index * 73 % 254) shl 23
        // An odd multiplier gives distinct 23-bit fractions for the first 2^23 indices.
        val fraction = (index * 0x45d9f3b) and 0x7fffff
        Float.fromBits(sign or exponent or fraction)
    }
    private val floatToIntValues = generateFloatValues(INT_EXPONENT_COUNT)
    private val floatToLongValues = generateFloatValues(LONG_EXPONENT_COUNT)

    private fun generateDoubleValues(exponentCount: Int) = DoubleArray(BENCHMARK_SIZE) { index ->
        val sign = if ((index % 2) == 0) 0L else Long.MIN_VALUE
        // Keep every value within the target integer range, so rounding is always calculated.
        val exponent = (1022 + index * 73 % exponentCount).toLong() shl 52
        // An odd multiplier gives distinct 52-bit fractions for the first 2^52 indices.
        val fraction = (index.toLong() * 0x9E37_79B9_7F4A_7C15uL.toLong()) and 0x000f_ffff_ffff_ffffL
        Double.fromBits(sign or exponent or fraction)
    }

    private fun generateFloatValues(exponentCount: Int) = FloatArray(BENCHMARK_SIZE) { index ->
        val sign = if ((index % 2) == 0) 0 else Int.MIN_VALUE
        val exponent = (126 + index * 73 % exponentCount) shl 23
        // An odd multiplier gives distinct 23-bit fractions for the first 2^23 indices.
        val fraction = (index * 0x45d9f3b) and 0x7fffff
        Float.fromBits(sign or exponent or fraction)
    }

    @Benchmark
    fun doubleToIntFull(blackhole: Blackhole) {
        var accumulator = 0

        for (value in doubleValues) {
            accumulator += value.roundToInt()
        }

        blackhole.consume(accumulator)
    }

    @Benchmark
    fun doubleToLongFull(blackhole: Blackhole) {
        var accumulator = 0L

        for (value in doubleValues) {
            accumulator += value.roundToLong()
        }

        blackhole.consume(accumulator)
    }

    @Benchmark
    fun doubleToInt(blackhole: Blackhole) {
        var accumulator = 0

        for (value in doubleToIntValues) {
            accumulator += value.roundToInt()
        }

        blackhole.consume(accumulator)
    }

    @Benchmark
    fun doubleToLong(blackhole: Blackhole) {
        var accumulator = 0L

        for (value in doubleToLongValues) {
            accumulator += value.roundToLong()
        }

        blackhole.consume(accumulator)
    }

    @Benchmark
    fun floatToIntFull(blackhole: Blackhole) {
        var accumulator = 0

        for (value in floatValues) {
            accumulator += value.roundToInt()
        }

        blackhole.consume(accumulator)
    }

    @Benchmark
    fun floatToLongFull(blackhole: Blackhole) {
        var accumulator = 0L

        for (value in floatValues) {
            accumulator += value.roundToLong()
        }

        blackhole.consume(accumulator)
    }

    @Benchmark
    fun floatToInt(blackhole: Blackhole) {
        var accumulator = 0

        for (value in floatToIntValues) {
            accumulator += value.roundToInt()
        }

        blackhole.consume(accumulator)
    }

    @Benchmark
    fun floatToLong(blackhole: Blackhole) {
        var accumulator = 0L

        for (value in floatToLongValues) {
            accumulator += value.roundToLong()
        }

        blackhole.consume(accumulator)
    }
}
