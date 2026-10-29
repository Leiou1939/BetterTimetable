#!/usr/bin/env python3
"""生成字节级差量补丁（APK 增量更新用）。

格式（小端）:
    magic   8 字节  "TTPATCH1"
    fromSha 32 字节 旧文件 sha256
    toSha   32 字节 新文件 sha256
    toSize  8 字节  新文件长度
    ops     4 字节  操作条数
    操作:
      0x01 COPY  offset u64, length u32
      0x02 ADD   length u32, bytes

用法:
    python make_patch.py old.apk new.apk out.ttpatch
"""
import hashlib
import struct
import sys

MAGIC = b"TTPATCH1"
BLOCK = 16          # 索引块大小
MIN_COPY = 32       # 小于这个长度的匹配不值得，直接 ADD
MAX_CHAIN = 64      # 同一个块最多记几个位置


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.digest()


def build_index(old):
    index = {}
    for off in range(0, len(old) - BLOCK + 1, BLOCK):
        key = old[off:off + BLOCK]
        bucket = index.get(key)
        if bucket is None:
            index[key] = [off]
        elif len(bucket) < MAX_CHAIN:
            bucket.append(off)
    return index


def extend(old, new, old_off, new_off):
    """从 (old_off, new_off) 起向后延伸匹配长度。"""
    limit = min(len(old) - old_off, len(new) - new_off)
    n = BLOCK
    while n + 8 <= limit and old[old_off + n:old_off + n + 8] == new[new_off + n:new_off + n + 8]:
        n += 8
    while n < limit and old[old_off + n] == new[new_off + n]:
        n += 1
    return n


def make_ops(old, new):
    index = build_index(old)
    ops = []
    add = bytearray()

    def flush_add():
        if add:
            ops.append((0x02, bytes(add)))
            add.clear()

    i = 0
    n = len(new)
    while i < n:
        best_len = 0
        best_off = -1
        if i + BLOCK <= n:
            for cand in index.get(new[i:i + BLOCK], ()):
                length = extend(old, new, cand, i)
                if length > best_len:
                    best_len, best_off = length, cand
        if best_len >= MIN_COPY:
            flush_add()
            ops.append((0x01, best_off, best_len))
            i += best_len
        else:
            add.append(new[i])
            i += 1
    flush_add()
    return ops


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 1
    old_path, new_path, out_path = sys.argv[1:4]
    old = open(old_path, "rb").read()
    new = open(new_path, "rb").read()
    ops = make_ops(old, new)

    body = bytearray()
    body += MAGIC
    body += sha256_file(old_path)
    body += sha256_file(new_path)
    body += struct.pack("<Q", len(new))
    body += struct.pack("<I", len(ops))
    for op in ops:
        if op[0] == 0x01:
            body += struct.pack("<BQI", 0x01, op[1], op[2])
        else:
            body += struct.pack("<BI", 0x02, len(op[1]))
            body += op[1]
    open(out_path, "wb").write(bytes(body))

    new_size = max(1, len(new))
    print(f"old {len(old)/1048576:.1f} MB, new {len(new)/1048576:.1f} MB, "
          f"patch {len(body)/1048576:.1f} MB "
          f"({len(body)*100/new_size:.1f}% of new), ops {len(ops)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
