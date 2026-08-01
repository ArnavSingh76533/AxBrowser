package com.akay.feature.browser.fingerprint

import kotlin.math.abs

private val HARDWARE_CONCURRENCY_POOL = listOf(4, 6, 8, 12)
private val DEVICE_MEMORY_POOL = listOf(4, 8)
private val GPU_POOL = listOf(
    "Qualcomm" to "Adreno (TM) 640",
    "Qualcomm" to "Adreno (TM) 650",
    "Qualcomm" to "Adreno (TM) 730",
    "ARM" to "Mali-G78",
    "ARM" to "Mali-G710"
)

/**
 * Builds a JS snippet that overrides the most commonly fingerprinted browser
 * signals (hardwareConcurrency, deviceMemory, canvas output, WebGL vendor
 * strings) with values deterministically derived from [seed] - same seed
 * always looks the same to a site within one "identity", but two different
 * seeds (or after "New device identity") look like two different devices.
 *
 * Must run via WebViewCompat.addDocumentStartJavaScript so it executes
 * before the page's own scripts (including any fingerprinting library) -
 * injecting this after onPageFinished would be too late.
 */
object FingerprintSpoofing {

    fun buildScript(seed: String, spoofCanvas: Boolean, spoofWebGl: Boolean, spoofHardware: Boolean): String {
        val hash = abs(seed.hashCode())
        val hwConcurrency = HARDWARE_CONCURRENCY_POOL[hash % HARDWARE_CONCURRENCY_POOL.size]
        val deviceMemory = DEVICE_MEMORY_POOL[(hash / 7) % DEVICE_MEMORY_POOL.size]
        val (gpuVendor, gpuRenderer) = GPU_POOL[(hash / 13) % GPU_POOL.size]
        val noiseSeed = hash % 100000

        val hardwareBlock = if (spoofHardware) {
            """
            try {
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return $hwConcurrency; } });
                Object.defineProperty(navigator, 'deviceMemory', { get: function() { return $deviceMemory; } });
                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                Object.defineProperty(navigator, 'plugins', { get: function() { return []; } });
                Object.defineProperty(navigator, 'mimeTypes', { get: function() { return []; } });
            } catch (e) {}
            """.trimIndent()
        } else ""

        val canvasBlock = if (spoofCanvas) {
            """
            try {
                var __axSeed = $noiseSeed;
                function __axRand() { __axSeed = (__axSeed * 1103515245 + 12345) & 0x7fffffff; return __axSeed / 0x7fffffff; }
                var __origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                HTMLCanvasElement.prototype.toDataURL = function() {
                    var ctx = this.getContext('2d');
                    if (ctx) {
                        try {
                            var imgData = ctx.getImageData(0, 0, this.width, this.height);
                            for (var i = 0; i < imgData.data.length; i += 4) {
                                var n = Math.floor(__axRand() * 3) - 1;
                                imgData.data[i] = Math.min(255, Math.max(0, imgData.data[i] + n));
                            }
                            ctx.putImageData(imgData, 0, 0);
                        } catch (e) {}
                    }
                    return __origToDataURL.apply(this, arguments);
                };
                var __origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                CanvasRenderingContext2D.prototype.getImageData = function() {
                    var result = __origGetImageData.apply(this, arguments);
                    for (var i = 0; i < result.data.length; i += 4) {
                        var n = Math.floor(__axRand() * 3) - 1;
                        result.data[i] = Math.min(255, Math.max(0, result.data[i] + n));
                    }
                    return result;
                };
            } catch (e) {}
            """.trimIndent()
        } else ""

        val webglBlock = if (spoofWebGl) {
            """
            try {
                function __axPatchGl(proto) {
                    var __origGetParameter = proto.getParameter;
                    proto.getParameter = function(param) {
                        if (param === 37445) return '$gpuVendor';
                        if (param === 37446) return '$gpuRenderer';
                        return __origGetParameter.apply(this, arguments);
                    };
                }
                if (window.WebGLRenderingContext) __axPatchGl(WebGLRenderingContext.prototype);
                if (window.WebGL2RenderingContext) __axPatchGl(WebGL2RenderingContext.prototype);
            } catch (e) {}
            """.trimIndent()
        } else ""

        return """
            (function() {
                $hardwareBlock
                $canvasBlock
                $webglBlock
            })();
        """.trimIndent()
    }
}
