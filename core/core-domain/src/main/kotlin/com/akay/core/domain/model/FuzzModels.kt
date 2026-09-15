package com.akay.core.domain.model

data class FuzzJob(
    val id: Long = 0,
    val name: String,
    val engine: String = "sniper", // sniper | clusterbomb
    val requestTemplate: String,   // raw request with §position§ markers
    val payloadSets: String = "[]", // JSON array of payload arrays (clusterbomb) / single array (sniper)
    val threads: Int = 2,
    val requestCap: Int = 500,
    val delayMs: Long = 0,
    val status: String = "pending", // pending | running | done | stopped | error
    val createdAt: Long = System.currentTimeMillis()
)

data class FuzzResult(
    val id: Long = 0,
    val jobId: Long,
    val payload: String,
    val status: Int = 0,
    val length: Int = 0,
    val timeMs: Long = 0,
    val hit: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
