package com.liangyuchen.timetable.update

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 字节级差量补丁的应用器（配合 tools/make_patch.py 生成的 .ttpatch）。
 *
 * 为什么需要它：APK 的签名覆盖整个文件，只有「旧包 + 补丁 = 新包（逐字节一致）」，
 * 新包才装得上。所以这里保证：还原后先比 sha256，不一致就不装，回退到整包下载。
 *
 * 格式见 tools/make_patch.py 头部注释。
 */
object BsPatch {

    private val MAGIC = "TTPATCH1".toByteArray(Charsets.US_ASCII)

    class PatchInfo(val fromSha: String, val toSha: String, val toSize: Long)

    /** 只读补丁头，用来判断"本机这个包能不能用这个补丁"。 */
    fun readInfo(patch: File): PatchInfo? = runCatching {
        RandomAccessFile(patch, "r").use { raf ->
            val magic = ByteArray(MAGIC.size)
            raf.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            val from = ByteArray(32).also { raf.readFully(it) }
            val to = ByteArray(32).also { raf.readFully(it) }
            val size = readLongLe(raf)
            PatchInfo(hex(from), hex(to), size)
        }
    }.getOrNull()

    /**
     * 把 [patch] 应用到 [oldFile]，写出 [target]。
     * @return 还原后的 sha256；若与补丁里记录的不一致，返回 null 并删除输出。
     */
    fun apply(oldFile: File, patch: File, target: File): String? {
        val info = readInfo(patch) ?: return null
        if (sha256(oldFile) != info.fromSha) return null          // 本机的包不是补丁对应的那一版

        runCatching {
            RandomAccessFile(patch, "r").use { raf ->
                // 跳过 magic + fromSha + toSha，读 toSize 与 ops
                raf.seek((MAGIC.size + 32 + 32).toLong())
                readLongLe(raf)
                val ops = readIntLe(raf)
                RandomAccessFile(oldFile, "r").use { src ->
                    target.outputStream().buffered(1 shl 20).use { out ->
                        val buffer = ByteArray(1 shl 16)
                        for (k in 0 until ops) {
                            when (raf.readByte().toInt() and 0xFF) {
                                0x01 -> {                       // COPY
                                    val off = readLongLe(raf)
                                    val len = readIntLe(raf)
                                    src.seek(off)
                                    var left = len
                                    while (left > 0) {
                                        val n = minOf(buffer.size, left)
                                        src.readFully(buffer, 0, n)
                                        out.write(buffer, 0, n)
                                        left -= n
                                    }
                                }
                                0x02 -> {                       // ADD
                                    var left = readIntLe(raf)
                                    while (left > 0) {
                                        val n = minOf(buffer.size, left)
                                        raf.readFully(buffer, 0, n)
                                        out.write(buffer, 0, n)
                                        left -= n
                                    }
                                }
                                else -> error("补丁格式错误")
                            }
                        }
                    }
                }
            }
        }.onFailure {
            runCatching { target.delete() }
            return null
        }

        val got = sha256(target)
        return if (got == info.toSha) got else {
            runCatching { target.delete() }
            null
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun readLongLe(raf: RandomAccessFile): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((raf.readByte().toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        var v = 0
        for (i in 0 until 4) {
            v = v or ((raf.readByte().toInt() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
