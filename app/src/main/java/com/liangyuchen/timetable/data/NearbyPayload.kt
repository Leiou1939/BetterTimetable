package com.liangyuchen.timetable.data

import org.json.JSONObject
import java.util.zip.CRC32

/**
 * 「快速添加」走蓝牙传输的那一小段数据：名字 + 学校 + 头像色 + 课表。
 *
 * 编成一个 JSON 字节串，再切成不超过 [CHUNK] 字节的片段逐个读。
 * BLE 一次读最多 ~512 字节，所以必须切片。
 */
data class NearbyPayload(
    val name: String,
    val schoolId: String,
    val colorIndex: Int,
    val scheduleJson: String,
) {
    fun encode(): ByteArray = JSONObject()
        .put("v", FORMAT_VERSION)
        .put("name", name)
        .put("school", schoolId)
        .put("color", colorIndex)
        .put("schedule", scheduleJson)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        const val FORMAT_VERSION = 1
        const val CHUNK = 480
        const val SERVICE_UUID = "1157a0b1-9a41-4f5c-9a2e-1b7c3d5e6f01"
        const val META_UUID = "1157a0b1-9a41-4f5c-9a2e-1b7c3d5e6f02"
        const val DATA_UUID = "1157a0b1-9a41-4f5c-9a2e-1b7c3d5e6f03"

        fun decode(bytes: ByteArray): NearbyPayload? = runCatching {
            val o = JSONObject(bytes.toString(Charsets.UTF_8))
            if (o.optInt("v", 0) != FORMAT_VERSION) return null
            val name = o.optString("name").ifBlank { "同学" }
            val schedule = o.optString("schedule")
            if (schedule.isBlank()) return null
            NearbyPayload(
                name = name,
                schoolId = o.optString("school"),
                colorIndex = o.optInt("color", 0),
                scheduleJson = schedule,
            )
        }.getOrNull()

        /** 数据长度 + CRC，供接收端核对完整性。 */
        fun meta(bytes: ByteArray): ByteArray {
            val crc = CRC32().apply { update(bytes) }.value
            val out = ByteArray(8)
            for (i in 0 until 4) {
                out[i] = ((bytes.size shr (8 * i)) and 0xFF).toByte()
                out[4 + i] = ((crc.toInt() shr (8 * i)) and 0xFF).toByte()
            }
            return out
        }

        fun readMeta(raw: ByteArray): Pair<Int, Long>? {
            if (raw.size < 8) return null
            var size = 0
            var crc = 0L
            for (i in 0 until 4) {
                size = size or ((raw[i].toInt() and 0xFF) shl (8 * i))
                crc = crc or ((raw[4 + i].toLong() and 0xFF) shl (8 * i))
            }
            return size to crc
        }

        fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    }
}
