package com.jarvis.app.tts

/**
 * Edge 语音 v2 协议的 Sec-MS-GEC 计算（移植自 edge-tts 7.3.1 的 drm.py）。
 * 注意：这里的 SHA-256 是微软定制的变体（初始状态由数据前 7 字节派生），
 * 不是标准 SHA-256，不能用 MessageDigest 代替；CS64 也是定制算法。
 * 常量由脚本直接从 drm.py 提取，未手抄；逻辑函数与 Python 版逐行对应，
 * 其中 SHA-256 的 64 轮用标准单轮循环实现（已验证与 Python 的 8 轮展开版等价）。
 */
object EdgeGec {
    private const val MASK = 0xFFFFFFFFL
    private const val WIN_EPOCH = 11644473600.0
    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL, 0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )
    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL, 0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )
    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L,
    )
    private val SHA256_ROUND = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private const val SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0L
    private val SHA256_BLOCK: LongArray
        get() {
            val out = SHA256_ROUND.copyOf()
            out[14] = SHA256_ADJUSTMENT
            return out
        }
    private val CS64_COEFFICIENTS = arrayOf(
        Pair(0xE82E91B1L, 0xB32700D0L),
        Pair(0x44DE24C6L, 0x88DFF51DL),
        Pair(0x3BEB65BCL, 0x29514038L),
        Pair(0xC89C5933L, 0xB328CBDFL),
        Pair(0x8D1EC211L, 0xD405B4D5L),
        Pair(0x314EF5D5L, 0x26E55F3CL),
        Pair(0xE92491B1L, 0x607801C6L),
        Pair(0xF0A5B9D5L, 0x82B2C1FBL),
        Pair(0x06064CF3L, 0xFA51A3DAL),
        Pair(0xDA79FA60L, 0x207CC464L),
        Pair(0xBED20560L, 0x3C3C74F3L),
        Pair(0x89F9C9F8L, 0xD2D14B61L),
        Pair(0x35FA38C6L, 0xBB04A2C1L),
        Pair(0xCB49E3C6L, 0xC5626167L),
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    private fun rol16(v: Long): Long = (((v and MASK) shl 16) or ((v and MASK) ushr 16)) and MASK

    private fun rotr(v: Long, r: Int): Long = (((v and MASK) ushr r) or ((v and MASK) shl (32 - r))) and MASK

    private fun wordsLe4Even(data: ByteArray): List<Long> {
        val padded = if (data.size % 4 == 0) data else data + ByteArray(4 - data.size % 4)
        val words = ArrayList<Long>()
        var i = 0
        while (i < padded.size) {
            words.add(
                (padded[i].toLong() and 0xFF) or
                    ((padded[i + 1].toLong() and 0xFF) shl 8) or
                    ((padded[i + 2].toLong() and 0xFF) shl 16) or
                    ((padded[i + 3].toLong() and 0xFF) shl 24)
            )
            i += 4
        }
        if (words.size % 2 == 1) words.add(0L)
        return words
    }

    private fun sigma0(v: Long): Long =
        ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and MASK

    private fun sigma1(v: Long): Long =
        ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and MASK

    /** 标准 SHA-256 单轮循环（已验证与 Python 的 8 轮展开版逐轮等价）。 */
    private fun sha256Rounds(state: LongArray, words: LongArray, firstRound: Int = 0): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (firstRound == 14) SHA256_BLOCK else SHA256_ROUND
        for (index in firstRound until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = (h + s1 + ch + k[index] + words[index]) and MASK
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = (s0 + maj) and MASK
            val na = (t1 + t2) and MASK
            val nb = a; val nc = b; val nd = c; val ne = (d + t1) and MASK
            val nf = e; val ng = f; val nh = g
            a = na; b = nb; c = nc; d = nd; e = ne; f = nf; g = ng; h = nh
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: MutableList<Long>, firstWord: Int = 16) {
        var index = firstWord
        while (index < 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words.add((s1 + words[index - 7] + s0 + words[index - 16]) and MASK)
            index++
        }
    }

    private fun sha256Init(data: ByteArray): LongArray {
        val words = MutableList<Long>(14) { 0L }
        words.add(
            ((data[0].toLong() and 0xFF) shl 16) or
                ((data[1].toLong() and 0xFF) shl 8) or
                (data[2].toLong() and 0xFF)
        )
        words.add(
            ((data[3].toLong() and 0xFF) shl 24) or
                ((data[4].toLong() and 0xFF) shl 16) or
                ((data[5].toLong() and 0xFF) shl 8) or
                (data[6].toLong() and 0xFF)
        )
        for (index in 16..29) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[index - 2]) xor SIGMA1_ADJUSTMENT
                else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[index - 15]) xor SIGMA0_ADJUSTMENT
                else sigma0(words[index - 15])
            }
            words.add(value and MASK)
        }
        words.add(
            (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                SHA256_ADJUSTMENT - SHA256_ROUND[14]) and MASK
        )
        expandSchedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words.toLongArray(), 14)
        return LongArray(8) { (SHA256_INITIAL[it] + working[it]) and MASK }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val length = 57 + data.size
        val zeroCount = ((55 - length) % 64 + 64) % 64
        val padded = ByteArray((data.size - 7) + 1 + zeroCount + 8)
        var pos = 0
        for (i in 7 until data.size) padded[pos++] = data[i]
        padded[pos++] = 0x80.toByte()
        pos += zeroCount
        val bitLen = length.toLong() * 8
        for (i in 7 downTo 0) padded[pos++] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        var state = sha256Init(data)
        var offset = 0
        while (offset < padded.size) {
            val words = MutableList<Long>(16) { 0L }
            for (i in 0 until 16) {
                val o = offset + i * 4
                words[i] = ((padded[o].toLong() and 0xFF) shl 24) or
                    ((padded[o + 1].toLong() and 0xFF) shl 16) or
                    ((padded[o + 2].toLong() and 0xFF) shl 8) or
                    (padded[o + 3].toLong() and 0xFF)
            }
            expandSchedule(words)
            val working = sha256Rounds(state, words.toLongArray())
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = state[i]
            out[i * 4] = ((v ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (v and 0xFF).toByte()
        }
        return out
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        var keyA = (digest[0].toLong() and 0xFF) or
            ((digest[1].toLong() and 0xFF) shl 8) or
            ((digest[2].toLong() and 0xFF) shl 16) or
            ((digest[3].toLong() and 0xFF) shl 24)
        var keyB = (digest[4].toLong() and 0xFF) or
            ((digest[5].toLong() and 0xFF) shl 8) or
            ((digest[6].toLong() and 0xFF) shl 16) or
            ((digest[7].toLong() and 0xFF) shl 24)
        keyA = keyA or 1
        keyB = keyB or 1
        val factors = longArrayOf((keyA * CS64_FACTOR) and MASK, (keyB * CS64_FACTOR) and MASK)
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for ((index, pair) in CS64_COEFFICIENTS.withIndex()) {
            terms.add((pair.first * factors[index % 2]) and MASK)
            offsets.add(pair.second)
        }
        val prefixed = byteArrayOf(0) + data
        for ((index, word) in wordsLe4Even(prefixed).withIndex()) {
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and MASK)
            offsets.add(offset and MASK)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        var index = 0
        while (index < terms.size) {
            val x = terms[index]; val y = terms[index + 1]
            var t = rol16((offsets[index] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and MASK
            t = rol16((offsets[index + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK
            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc += state
            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            index += 2
        }
        return Pair(swapLast xor state, swapAcc xor acc)
    }

    private fun hexLe(w: Long): String =
        "%02X%02X%02X%02X".format(
            w and 0xFF, (w ushr 8) and 0xFF, (w ushr 16) and 0xFF, (w ushr 24) and 0xFF
        )

    private fun tokenHash(data: ByteArray): Pair<String, String> {
        val digest = sha256Digest(data)
        val (w0, w1) = cs64Digest(data, digest)
        val cs64 = hexLe(w0) + hexLe(w1)
        val sha = digest.joinToString("") { "%02X".format(it.toLong() and 0xFF) }
        return Pair(cs64, sha)
    }

    /** v2 Sec-MS-GEC：格式为“CS64(16 hex)-SHA256(64 hex)”；时间取整到 5 分钟的 Windows ticks。 */
    fun generate(token: String, unixSec: Double = System.currentTimeMillis() / 1000.0): String {
        var ticks = unixSec + WIN_EPOCH
        ticks -= ticks % 300.0
        ticks *= 10000000.0
        val tickStr = String.format(java.util.Locale.US, "%.0f", ticks)
        val data = (tickStr + token).toByteArray(Charsets.US_ASCII)
        val (cs64, sha) = tokenHash(data)
        return "$cs64-$sha"
    }
}
