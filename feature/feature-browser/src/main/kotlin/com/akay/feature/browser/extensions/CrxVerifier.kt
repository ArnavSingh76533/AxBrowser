package com.akay.feature.browser.extensions

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Bounded CRX3 protobuf reader; verifies the signed archive AND its extension identity. */
object CrxVerifier {
    data class Verified(val id: String, val archive: ByteArray)
    fun verify(bytes: ByteArray, expectedId: String? = null): Verified {
        require(bytes.size in 13..(32 * 1024 * 1024)) { "Invalid package size" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "Cr24" && le(bytes, 4) == 3) { "Only signed CRX3 packages are supported" }
        val size = le(bytes, 8)
        require(size in 1..(1024 * 1024) && 12L + size < bytes.size) { "Invalid CRX header" }
        val header = fields(bytes.copyOfRange(12, 12 + size))
        val signed = header[10000]?.singleOrNull() ?: error("Missing signed header")
        val idBytes = fields(signed)[1]?.singleOrNull() ?: error("Missing extension identity")
        require(idBytes.size == 16)
        val id = idBytes.joinToString("") { b -> "${('a'.code + ((b.toInt() and 255) shr 4)).toChar()}${('a'.code + (b.toInt() and 15)).toChar()}" }
        require(expectedId == null || expectedId == id) { "Package does not match the selected Store extension" }
        val archive = bytes.copyOfRange(12 + size, bytes.size)
        val signedPrefix = "CRX3 SignedData\u0000".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(signed.size).array() + signed
        var identityVerified = false
        for ((field, algorithm) in listOf(2 to "RSA", 3 to "EC")) {
            for (proofBytes in header[field].orEmpty()) {
                val proof = fields(proofBytes)
                val key = proof[1]?.singleOrNull() ?: error("Missing public key")
                val signature = proof[2]?.singleOrNull() ?: error("Missing signature")
                val valid = runCatching {
                    Signature.getInstance(if (algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA").run {
                        initVerify(KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(key)))
                        update(signedPrefix); update(archive); verify(signature)
                    }
                }.getOrDefault(false)
                require(valid) { "Invalid CRX signature" }
                if (MessageDigest.getInstance("SHA-256").digest(key).copyOf(16).contentEquals(idBytes)) identityVerified = true
            }
        }
        require(identityVerified) { "No signature proves this extension identity" }
        require(archive.size >= 4 && archive[0] == 80.toByte() && archive[1] == 75.toByte()) { "Invalid CRX archive" }
        return Verified(id, archive)
    }
    private fun le(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun fields(bytes: ByteArray): Map<Int, List<ByteArray>> {
        var pos = 0
        fun varint(): Int {
            var result = 0L
            for (shift in 0..28 step 7) {
                require(pos < bytes.size) { "Truncated protobuf" }
                val b = bytes[pos++].toInt() and 255
                result = result or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) { require(result <= Int.MAX_VALUE); return result.toInt() }
            }
            error("Invalid protobuf integer")
        }
        val result = mutableMapOf<Int, MutableList<ByteArray>>()
        while (pos < bytes.size) {
            val tag = varint()
            require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> varint()
                2 -> {
                    val length = varint()
                    require(length <= bytes.size - pos) { "Truncated protobuf field" }
                    result.getOrPut(tag ushr 3) { mutableListOf() }.add(bytes.copyOfRange(pos, pos + length))
                    pos += length
                }
                else -> error("Unsupported CRX header wire type")
            }
        }
        return result
    }
}
