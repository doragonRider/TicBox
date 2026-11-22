package dev.ticbox.companion.protocol

/**
 * Original, systematic Cauchy Reed-Solomon erasure code over GF(256), polynomial 0x11d.
 * The generator is [I; C], C[r,c] = inverse(r xor (c + 4)). The two evaluation sets
 * are disjoint, so every square Cauchy submatrix is invertible. Only missing data rows
 * are solved, at most four; received data is never re-encoded on a healthy receiver.
 * See docs/spec/remote-screen.md for the mathematical references and wire profile.
 */
internal object ScreenReedSolomon {
    const val DATA = 16
    const val PARITY = 4
    private val products = Array(256) { coefficient -> ByteArray(256) { multiplySlow(coefficient, it).toByte() } }
    private val inverse = IntArray(256).also { table ->
        for (value in 1..255) table[value] = (1..255).first { products[value][it].toInt() and 255 == 1 }
    }
    private val coefficients = Array(PARITY) { row -> IntArray(DATA) { column -> inverse[row xor (column + PARITY)] } }

    fun parityCount(dataShards: Int): Int {
        require(dataShards in 1..DATA)
        return (dataShards + 3) / 4
    }

    fun encode(data: Array<ByteArray>, checkCancelled: () -> Unit = {}): Array<ByteArray> {
        require(data.size in 1..DATA)
        val length = data.first().size
        require(data.all { it.size == length })
        return Array(parityCount(data.size)) { row ->
            ByteArray(length).also { output ->
                for (column in data.indices) {
                    checkCancelled()
                    addProduct(output, data[column], coefficients[row][column])
                }
            }
        }
    }

    /** Reusable contiguous workspaces used by both portable and native senders. */
    fun encodeInto(data: ByteArray, count: Int, length: Int, parity: ByteArray, checkCancelled: () -> Unit = {}) {
        require(count in 1..DATA && length in 0..1152 && data.size >= count * length && parity.size >= parityCount(count) * length)
        parity.fill(0, 0, parityCount(count) * length)
        for (row in 0 until parityCount(count)) for (column in 0 until count) {
            checkCancelled()
            val table = products[coefficients[row][column]]
            val destination = row * length
            val source = column * length
            for (index in 0 until length) parity[destination + index] =
                (parity[destination + index].toInt() xor table[data[source + index].toInt() and 255].toInt()).toByte()
        }
    }

    /** Returns only absent data shards. Null means more erasures than available parity. */
    fun recover(data: Array<ByteArray?>, parity: Array<ByteArray?>, length: Int): Map<Int, ByteArray>? {
        require(data.size in 1..DATA && parity.size == parityCount(data.size) && length >= 0)
        require(data.all { it == null || it.size == length } && parity.all { it == null || it.size == length })
        val missing = data.indices.filter { data[it] == null }
        if (missing.isEmpty()) return emptyMap()
        val rows = parity.indices.filter { parity[it] != null }.take(missing.size)
        if (rows.size < missing.size) return null
        val matrix = Array(missing.size) { r -> IntArray(missing.size) { c -> coefficients[rows[r]][missing[c]] } }
        val inverseMatrix = invert(matrix)
        val right = Array(missing.size) { r -> checkNotNull(parity[rows[r]]).copyOf().also { output ->
            for (column in data.indices) data[column]?.let { addProduct(output, it, coefficients[rows[r]][column]) }
        } }
        return missing.mapIndexed { row, index -> index to ByteArray(length).also { output ->
            for (column in right.indices) addProduct(output, right[column], inverseMatrix[row][column])
        } }.toMap()
    }

    private fun invert(input: Array<IntArray>): Array<IntArray> {
        val count = input.size
        val left = Array(count) { input[it].copyOf() }
        val right = Array(count) { r -> IntArray(count) { c -> if (r == c) 1 else 0 } }
        for (column in 0 until count) {
            val pivot = (column until count).firstOrNull { left[it][column] != 0 } ?: error("singular_fec_matrix")
            if (pivot != column) {
                val l = left[pivot]; left[pivot] = left[column]; left[column] = l
                val r = right[pivot]; right[pivot] = right[column]; right[column] = r
            }
            val scale = inverse[left[column][column]]
            for (c in 0 until count) {
                left[column][c] = product(left[column][c], scale)
                right[column][c] = product(right[column][c], scale)
            }
            for (row in 0 until count) if (row != column) {
                val coefficient = left[row][column]
                if (coefficient != 0) for (c in 0 until count) {
                    left[row][c] = left[row][c] xor product(coefficient, left[column][c])
                    right[row][c] = right[row][c] xor product(coefficient, right[column][c])
                }
            }
        }
        return right
    }

    private fun addProduct(output: ByteArray, input: ByteArray, coefficient: Int) {
        val row = products[coefficient]
        for (index in output.indices) output[index] = (output[index].toInt() xor row[input[index].toInt() and 255].toInt()).toByte()
    }
    private fun product(left: Int, right: Int) = products[left][right].toInt() and 255
    private fun multiplySlow(left: Int, right: Int): Int {
        var a = left; var b = right; var output = 0
        while (b != 0) {
            if (b and 1 != 0) output = output xor a
            a = a shl 1
            if (a and 256 != 0) a = a xor 0x11d
            b = b ushr 1
        }
        return output
    }
}

/** Fixed profile; all shard/block positions are derived from authenticated total/index fields. */
internal object ScreenFecLayout {
    const val DATA = ScreenReedSolomon.DATA
    const val PARITY = ScreenReedSolomon.PARITY
    const val BLOCK = DATA + PARITY
    fun dataShards(total: Int) = maxOf(1, (total + ScreenUdpCipher.PAYLOAD - 1) / ScreenUdpCipher.PAYLOAD)
    fun blocks(total: Int) = (dataShards(total) + DATA - 1) / DATA
    fun dataInBlock(total: Int, block: Int) = minOf(DATA, dataShards(total) - block * DATA)
    fun parityShards(total: Int): Int {
        val data = dataShards(total)
        return (data / DATA) * PARITY + if (data % DATA == 0) 0 else ScreenReedSolomon.parityCount(data % DATA)
    }
    fun wireShards(total: Int) = dataShards(total) + parityShards(total)
    fun shardBytes(total: Int) = minOf(total, ScreenUdpCipher.PAYLOAD)
    fun reservedBytes(total: Int): Int = total + parityShards(total) * shardBytes(total) + blocks(total) * 256
    fun dataOffset(block: Int, shard: Int) = (block * DATA + shard) * ScreenUdpCipher.PAYLOAD
}
