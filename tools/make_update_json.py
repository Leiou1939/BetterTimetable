#!/usr/bin/env python3
"""生成 update.json（App 用它决定"整包下载"还是"打补丁"）。

用法:
    python make_update_json.py <version> <apk> [patch] > update.json
"""
import hashlib
import json
import sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_patch_hashes(path):
    """补丁头：magic(8) + fromSha(32) + toSha(32)"""
    with open(path, "rb") as f:
        head = f.read(8 + 32 + 32)
    if len(head) < 72 or head[:8] != b"TTPATCH1":
        return None, None
    return head[8:40].hex(), head[40:72].hex()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    version = sys.argv[1]
    apk = sys.argv[2]
    patch = sys.argv[3] if len(sys.argv) > 3 else None

    data = {
        "version": version,
        "apk": apk.split("/")[-1].split("\\")[-1],
        "apkSha256": sha256(apk),
    }
    if patch:
        from_sha, to_sha = read_patch_hashes(patch)
        if from_sha:
            data["patch"] = patch.split("/")[-1].split("\\")[-1]
            data["patchSize"] = __import__("os").path.getsize(patch)
            data["patchFromSha256"] = from_sha
            data["patchToSha256"] = to_sha
    json.dump(data, sys.stdout, ensure_ascii=False, indent=2)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
