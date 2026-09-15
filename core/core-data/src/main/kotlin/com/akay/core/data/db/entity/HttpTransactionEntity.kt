package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Proxy History: persisted request/response pairs (Room v7). */
@Entity(
    tableName = "http_transactions",
    indices = [Index("session_id"), Index("created_at")]
)
data class HttpTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val method: String,
    val url: String,
    @ColumnInfo(name = "http_version") val httpVersion: String,
    @ColumnInfo(name = "headers_json") val headersJson: String, // JSON [[k,v],...] preserves order + duplicates
    @ColumnInfo(name = "body_text") val bodyText: String,
    @ColumnInfo(name = "response_status") val responseStatus: Int?,
    @ColumnInfo(name = "response_headers_json") val responseHeadersJson: String,
    @ColumnInfo(name = "response_body_text") val responseBodyText: String,
    @ColumnInfo(name = "response_time_ms") val responseTimeMs: Long,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** Intruder job definition (Room v7). */
@Entity(tableName = "fuzz_jobs")
data class FuzzJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val engine: String,            // sniper | clusterbomb
    @ColumnInfo(name = "request_template") val requestTemplate: String,
    @ColumnInfo(name = "payload_sets_json") val payloadSetsJson: String,
    val threads: Int,
    @ColumnInfo(name = "request_cap") val requestCap: Int,
    @ColumnInfo(name = "delay_ms") val delayMs: Long,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** Intruder per-request outcome (Room v7). */
@Entity(tableName = "fuzz_results", indices = [Index("job_id")])
data class FuzzResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "job_id") val jobId: Long,
    val payload: String,
    val status: Int,
    val length: Int,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    val hit: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
