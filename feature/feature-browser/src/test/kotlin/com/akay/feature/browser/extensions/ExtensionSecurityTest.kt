package com.akay.feature.browser.extensions

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

class ExtensionSecurityTest {
    @TempDir lateinit var root: File
    @Test fun `host wildcard honors domain boundary and paths`() {
        val pattern = MatchPattern("https://*.example.com/private/*")
        assertTrue(pattern.matches("https://example.com/private/a?q=1"))
        assertTrue(pattern.matches("https://a.example.com/private/a"))
        assertFalse(pattern.matches("https://evilexample.com/private/a"))
        assertFalse(pattern.matches("https://example.com.evil.test/private/a"))
        assertFalse(pattern.matches("http://example.com/private/a"))
        assertFalse(pattern.matches("https://example.com/public/a"))
    }
    @Test fun `all urls does not grant file content javascript or data access`() {
        val pattern = MatchPattern("<all_urls>")
        listOf("file:///etc/passwd", "content://private/data", "javascript:alert(1)", "data:text/html,test", "https://user:pass@example.com/").forEach { assertFalse(pattern.matches(it), it) }
        assertTrue(pattern.matches("https://example.com/"))
    }
    @Test fun `resource paths cannot escape package or select absolute file`() {
        listOf("../outside", "/etc/passwd", "C:/private", "a/../../outside", "a\\..\\secret", "").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { resourceFile(root, path) }
        }
        assertEquals(File(root, "scripts/background.js").canonicalFile, resourceFile(root, "scripts/background.js"))
    }
    @Test fun `store action only accepts official HTTPS listing URLs`() {
        val id = "a".repeat(32)
        assertEquals(id, ExtensionInstaller.storeId("https://chromewebstore.google.com/detail/name/$id?hl=en"))
        assertEquals(id, ExtensionInstaller.storeId("https://chrome.google.com/webstore/detail/name/$id"))
        listOf("http://chromewebstore.google.com/detail/name/$id", "https://chromewebstore.google.com.evil.test/detail/name/$id", "https://user@chromewebstore.google.com/detail/name/$id", "https://chromewebstore.google.com:444/detail/name/$id", "https://chromewebstore.google.com/search/$id").forEach { assertNull(ExtensionInstaller.storeId(it)) }
    }
    @Test fun `manifest rejects unsupported execution worlds and frames`() {
        for (extra in listOf("\"all_frames\":true", "\"world\":\"MAIN\"", "\"match_about_blank\":true")) {
            assertThrows(IllegalArgumentException::class.java) { ExtensionManifest(JSONObject("""{"manifest_version":3,"name":"Test","version":"1","content_scripts":[{"matches":["https://example.com/*"],$extra}]}""")) }
        }
        assertThrows(IllegalArgumentException::class.java) { ExtensionManifest(JSONObject("""{"manifest_version":2,"name":"Test","version":"1"}""")) }
    }
    @Test fun `missing manifest entrypoint cannot install`() {
        val manifest = ExtensionManifest(JSONObject("""{"manifest_version":3,"name":"Test","version":"1.0","background":{"service_worker":"missing.js"}}"""))
        assertThrows(IllegalArgumentException::class.java) { manifest.validateFiles(root) }
    }
    @Test fun `bounded reader rejects oversized package`() {
        assertThrows(IllegalArgumentException::class.java) { ExtensionInstaller.readLimited(ByteArray(1025).inputStream(), 1024) }
        assertEquals(1024, ExtensionInstaller.readLimited(ByteArray(1024).inputStream(), 1024).size)
    }
    @Test fun `valid RSA and EC signed CRX3 archives verify`() {
        for (algorithm in listOf("RSA", "EC")) {
            val (crx, id) = signedCrx(algorithm)
            assertEquals(id, CrxVerifier.verify(crx, id).id)
        }
    }
    @Test fun `archive modification and wrong listing identity are rejected`() {
        val (crx, id) = signedCrx("RSA")
        val corrupted = crx.copyOf(); corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { CrxVerifier.verify(corrupted, id) }
        assertThrows(IllegalArgumentException::class.java) { CrxVerifier.verify(crx, "p".repeat(32)) }
    }
    @Test fun `malformed and unbounded CRX headers are rejected`() {
        for (bytes in listOf(ByteArray(4), "Cr24".toByteArray() + le(2) + le(1) + byteArrayOf(0), "Cr24".toByteArray() + le(3) + le(Int.MAX_VALUE) + byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) { CrxVerifier.verify(bytes) }
        }
    }
    private fun signedCrx(algorithm: String): Pair<ByteArray, String> {
        val generator = KeyPairGenerator.getInstance(algorithm)
        generator.initialize(if (algorithm == "RSA") 2048 else 256)
        val key = generator.generateKeyPair()
        val hash = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).copyOf(16)
        val id = hash.joinToString("") { "${('a'.code + ((it.toInt() and 255) shr 4)).toChar()}${('a'.code + (it.toInt() and 15)).toChar()}" }
        val signed = field(1, hash)
        val archive = byteArrayOf(80, 75, 3, 4, 1, 2, 3, 4)
        val signature = Signature.getInstance(if (algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA").run {
            initSign(key.private); update("CRX3 SignedData\u0000".toByteArray() + le(signed.size) + signed + archive); sign()
        }
        val proof = field(1, key.public.encoded) + field(2, signature)
        val header = field(if (algorithm == "RSA") 2 else 3, proof) + field(10000, signed)
        return ("Cr24".toByteArray() + le(3) + le(header.size) + header + archive) to id
    }
    private fun field(number: Int, data: ByteArray) = variable((number shl 3) or 2) + variable(data.size) + data
    private fun variable(value: Int): ByteArray {
        var n = value; val bytes = mutableListOf<Byte>()
        do { val b = n and 127; n = n ushr 7; bytes.add((b or if (n > 0) 128 else 0).toByte()) } while (n > 0)
        return bytes.toByteArray()
    }
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
