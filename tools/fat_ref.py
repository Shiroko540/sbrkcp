# -*- coding: utf-8 -*-
"""fat_ref.py -- FAT12/FAT16/FAT32 独立参考解码器（零第三方依赖）。

用途：给 sbrkcp 的 L1 解包（Fat.java）做独立对拍。本文件不参与产品路径，
只实现 README 描述的语义，便于离线核对路径集合、文件大小与逐文件 MD5。

用法：
    python tools\\fat_ref.py <镜像> --out <导出目录> [--manifest <csv>]
    python tools\\fat_ref.py <镜像> --verify <java清单.csv>

清单列：path,size,first_clus,read_bytes,md5
退出码：0 正常；2 参数错误；3 --verify 存在差异。
"""

from __future__ import print_function

import hashlib
import os
import struct
import sys

SEC_BUF = 1 << 20
DIR_LIMIT = 8 << 20          # 单个目录最多拼接 8 MB，防环
MAX_DEPTH = 12


class Bpb(object):
    def __init__(self, fh):
        fh.seek(0)
        self.bs = fh.read(512)
        if len(self.bs) < 512 or self.bs[510:512] != b"\x55\xAA":
            raise ValueError("not a FAT boot sector")
        (self.bytes_per_sec, self.sec_per_clus, self.rsvd, self.num_fats,
         self.root_ents, self.total16, self.media, self.fat16,
         self.sec_per_trk, self.heads, self.hidden,
         self.total32) = struct.unpack_from("<HBHBHHBHHHII", self.bs, 0x0B)
        root_off_32, self.fs_type_off = struct.unpack_from("<IH", self.bs, 0x2C)
        self.fat32 = struct.unpack_from("<I", self.bs, 0x24)[0]

        self.total_sec = self.total16 or self.total32
        self.fat_size = self.fat16 or self.fat32
        self.root_secs = (self.root_ents * 32 + self.bytes_per_sec - 1) // self.bytes_per_sec
        self.root_off = (self.rsvd + self.num_fats * self.fat_size) * self.bytes_per_sec
        self.root_samples = root_off_32
        self.data_off = self.root_off + self.root_secs * self.bytes_per_sec
        self.clus_bytes = self.sec_per_clus * self.bytes_per_sec
        self.cluster_count = ((self.total_sec - self.data_off // self.bytes_per_sec)
                              // self.sec_per_clus)
        if self.cluster_count < 4085:
            self.bits = 12
        elif self.cluster_count < 65525:
            self.bits = 16
        else:
            self.bits = 32
        self.label = ""
        if self.fs_type_off:
            fh.seek(self.fs_type_off)
            raw = fh.read(11)
            self.label = raw.decode("latin-1").strip("\x00 ")
        self.fat_off = self.rsvd * self.bytes_per_sec
        self.fat_bytes = self.fat_size * self.bytes_per_sec

    def describe(self):
        return ("FAT%d sec=%dB clus=%dB dirs=%d files=%d bytes=%d"
                % (self.bits, self.bytes_per_sec, self.clus_bytes,
                   0, 0, 0))


class Fat(object):
    def __init__(self, path):
        self.path = path
        self.fh = open(path, "rb")
        self.bpb = Bpb(self.fh)
        self.fh.seek(self.bpb.fat_off)
        self.fat = self.fh.read(self.bpb.fat_bytes)
        self.files = []          # (rel, size, first_clus, read_bytes, md5)
        self.dirs = 0
        self.anomalies = 0
        self.loops = 0
        self.seen_dirs = set()
        self.notes = []

    # ---- FAT entry -------------------------------------------------------
    def entry(self, n):
        bits = self.bpb.bits
        if bits == 12:
            off = n + (n >> 1)
            if off + 2 > len(self.fat):
                return 0xFFF
            v = self.fat[off] | (self.fat[off + 1] << 8)
            return v >> 4 if n & 1 else v & 0xFFF
        if bits == 16:
            off = n * 2
            if off + 2 > len(self.fat):
                return 0xFFFF
            return struct.unpack_from("<H", self.fat, off)[0]
        off = n * 4
        if off + 4 > len(self.fat):
            return 0xFFFFFFFF
        return struct.unpack_from("<I", self.fat, off)[0] & 0x0FFFFFFF

    def is_eoc(self, v):
        if self.bpb.bits == 12:
            return v >= 0xFF8
        if self.bpb.bits == 16:
            return v >= 0xFFF8
        return v >= 0x0FFFFFF8

    def is_bad(self, v):
        if self.bpb.bits == 12:
            return v == 0xFF7
        if self.bpb.bits == 16:
            return v == 0xFFF7
        return v == 0x0FFFFFF7

    def chain(self, start):
        """返回簇链；遇环/坏簇/越界即停，并计数。"""
        out = []
        seen = set()
        n = start
        limit = self.bpb.cluster_count + 8
        while 2 <= n < limit:
            if n in seen:
                self.loops += 1
                self.notes.append("CHAIN-LOOP at cluster %d" % n)
                break
            seen.add(n)
            out.append(n)
            v = self.entry(n)
            if self.is_eoc(v):
                break
            if self.is_bad(v) or v < 2:
                self.anomalies += 1
                self.notes.append("CHAIN-BAD cluster %d value 0x%X" % (n, v))
                break
            n = v
        return out

    # ---- raw io ----------------------------------------------------------
    def clus_off(self, n):
        return self.bpb.data_off + (n - 2) * self.bpb.clus_bytes

    def read_clus(self, n, want=None):
        self.fh.seek(self.clus_off(n))
        if want is None:
            return self.fh.read(self.bpb.clus_bytes)
        return self.fh.read(max(0, min(want, self.bpb.clus_bytes)))

    def read_file(self, start, size):
        h = hashlib.md5()
        got = bytearray()
        remaining = size
        for c in self.chain(start):
            if remaining <= 0:
                break
            chunk = self.read_clus(c, remaining)
            h.update(chunk)
            got.extend(chunk)
            remaining -= len(chunk)
        if remaining > 0:
            self.anomalies += 1
            self.notes.append("SHORT-READ need %d" % remaining)
        return bytes(got), h.hexdigest()

    def dir_bytes(self, start):
        data = bytearray()
        for c in self.chain(start):
            data.extend(self.read_clus(c))
            if len(data) >= DIR_LIMIT:
                self.anomalies += 1
                self.notes.append("DIR-TOO-BIG cluster %d" % start)
                break
        return bytes(data)

    # ---- directory walk --------------------------------------------------
    @staticmethod
    def _lfn_piece(entry):
        chars = []
        for off, cnt in ((1, 5), (14, 6), (28, 2)):
            for i in range(cnt):
                ch = struct.unpack_from("<H", entry, off + i * 2)[0]
                if ch == 0x0000:
                    return "".join(chars), True
                if ch == 0xFFFF:
                    continue
                chars.append(chr(ch))
        return "".join(chars), False

    def walk(self, start_clus, rel, depth):
        if depth > MAX_DEPTH:
            self.anomalies += 1
            self.notes.append("DEPTH-LIMIT %s" % (rel or "/"))
            return
        if start_clus >= 2:
            if start_clus in self.seen_dirs:
                self.loops += 1
                self.notes.append("DIR-LOOP %s" % (rel or "/"))
                return
            self.seen_dirs.add(start_clus)
            self.dirs += 1
        data = self.dir_bytes(start_clus) if start_clus >= 2 else self.read_root()
        pending = []
        for i in range(0, len(data) - 31, 32):
            e = data[i:i + 32]
            b0 = e[0]
            if b0 == 0x00:
                break
            if b0 == 0xE5:
                pending = []
                continue
            attr = e[11]
            if attr == 0x0F:
                pending.append(e)
                continue
            if attr & 0x08:
                pending = []
                continue
            if attr & 0x10:
                if e[0:11] in (b".          ", b"..         "):
                    pending = []
                    continue
                name = self._join_lfn(pending) or self._short_name(e)
                pending = []
                child = (rel + "/" + name) if rel else name
                clus = struct.unpack_from("<H", e, 26)[0]
                if self.bpb.bits == 32:
                    clus |= struct.unpack_from("<H", e, 20)[0] << 16
                if clus < 2:
                    self.anomalies += 1
                    self.notes.append("DIR-EMPTY-CLUS %s" % child)
                    continue
                self.walk(clus, child, depth + 1)
                continue
            name = self._join_lfn(pending) or self._short_name(e)
            pending = []
            child = (rel + "/" + name) if rel else name
            clus = struct.unpack_from("<H", e, 26)[0]
            if self.bpb.bits == 32:
                clus |= struct.unpack_from("<H", e, 20)[0] << 16
            size = struct.unpack_from("<I", e, 28)[0]
            if size == 0:
                self.files.append((child, 0, clus, 0, hashlib.md5().hexdigest()))
                continue
            blob, md5 = self.read_file(clus, size)
            self.files.append((child, size, clus, len(blob), md5))

    def read_root(self):
        self.fh.seek(self.bpb.root_off)
        return self.fh.read(self.bpb.root_secs * self.bpb.bytes_per_sec)

    @staticmethod
    def _short_name(e):
        raw = bytearray(e[0:11])
        if raw[0] == 0x05:
            raw[0] = 0xE5
        name = bytes(raw[0:8]).decode("latin-1").rstrip(" ")
        ext = bytes(raw[8:11]).decode("latin-1").rstrip(" ")
        ntres = e[12]
        # 0x08 仅基名小写，0x10 仅扩展名小写，两者独立
        if ntres & 0x08:
            name = name.lower()
        if ntres & 0x10:
            ext = ext.lower()
        return name + ("." + ext if ext else "")

    @staticmethod
    def _join_lfn(entries):
        if not entries:
            return ""
        ordered = sorted(entries, key=lambda x: x[0] & 0x1F)
        chars = []
        for e in ordered:
            piece, stop = Fat._lfn_piece(e)
            chars.append(piece)
            if stop:
                break
        return "".join(chars).rstrip("\x00")

    # ---- export ----------------------------------------------------------
    def extract(self, outdir):
        self.walk(0, "", 0)
        for rel, size, clus, read_bytes, md5 in self.files:
            dst = os.path.join(outdir, rel.replace("/", os.sep))
            d = os.path.dirname(dst)
            if d and not os.path.isdir(d):
                os.makedirs(d)
            if size == 0:
                open(dst, "wb").close()
                continue
            blob, _ = self.read_file(clus, size)
            with open(dst, "wb") as fh:
                fh.write(blob)
        return self.files

    def manifest_lines(self, root=""):
        lines = ["path,size,first_clus,read_bytes,md5"]
        for rel, size, clus, read_bytes, md5 in sorted(self.files):
            lines.append("%s,%d,%d,%d,%s" % (rel, size, clus, read_bytes, md5))
        return lines


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    img = argv[1]
    outdir = None
    verify = None
    manifest = None
    i = 2
    while i < len(argv):
        a = argv[i]
        if a == "--out" and i + 1 < len(argv):
            outdir = argv[i + 1]; i += 2
        elif a == "--verify" and i + 1 < len(argv):
            verify = argv[i + 1]; i += 2
        elif a == "--manifest" and i + 1 < len(argv):
            manifest = argv[i + 1]; i += 2
        else:
            print("unknown arg: %s" % a)
            return 2
    if not os.path.isfile(img):
        print("no such image: %s" % img)
        return 2

    fat = Fat(img)
    fat.walk(0, "", 0)
    total = sum(f[1] for f in fat.files)
    print("FAT%d clus=%dB dirs=%d files=%d bytes=%d loops=%d anomalies=%d"
          % (fat.bpb.bits, fat.bpb.clus_bytes, fat.dirs, len(fat.files),
             total, fat.loops, fat.anomalies))
    for n in fat.notes[:20]:
        print("  " + n)

    if manifest:
        with open(manifest, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(fat.manifest_lines()) + "\n")
        print("manifest -> %s" % manifest)

    if outdir:
        if not os.path.isdir(outdir):
            os.makedirs(outdir)
        fat.extract(outdir)
        print("exported -> %s" % outdir)

    if verify:
        ref = {}
        with open(verify, "r", encoding="utf-8") as fh:
            next(fh, None)
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                parts = line.rsplit(",", 4)
                if len(parts) != 5:
                    continue
                path, size, clus, read_bytes, md5 = parts
                ref[path] = (int(size), int(clus), int(read_bytes), md5)
        mine = dict((f[0], (f[1], f[2], f[3], f[4])) for f in fat.files)
        diff = 0
        for k in sorted(set(mine) | set(ref)):
            if mine.get(k) != ref.get(k):
                diff += 1
                if diff <= 10:
                    print("  DIFF %s\n    mine=%s\n    ref =%s"
                          % (k, mine.get(k), ref.get(k)))
        print("verify total=%d ref=%d diff=%d" % (len(mine), len(ref), diff))
        return 0 if diff == 0 else 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
