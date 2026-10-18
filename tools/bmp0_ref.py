#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BMP0.BIN 独立参考解码器（零第三方依赖）。

严格按 README §5.1/§5.2 的实测语义实现，只用于与 Java 实现逐条对拍，
不参与产品路径。两种用法：

    # 1) 导出每条的像素 MD5（BGRA 原始顺序，未换序，与 Java 侧口径一致）
    python tools/bmp0_ref.py samples/BMP0.BIN --out temp/ref_md5.txt

    # 2) 与 Java 侧导出的 MD5 清单逐条比对
    python tools/bmp0_ref.py samples/BMP0.BIN --verify temp/jav_md5.txt

MD5 清单每行格式：``<index> <md5|FAIL> <W>x<H> c<comp>``。
"""
from __future__ import print_function

import argparse
import hashlib
import struct
import sys


def read_full(path):
    """一次读满整个文件；单次 read() 允许短读，必须循环到 EOF。"""
    chunks = []
    with open(path, "rb") as fp:
        while True:
            b = fp.read(1 << 22)
            if not b:
                break
            chunks.append(b)
    return b"".join(chunks)


def parse_container(d):
    if d[4:8] != b"RKRS":
        raise ValueError("不是 RKRS 容器: %r" % (d[4:8],))
    table = struct.unpack_from("<I", d, 0x30)[0]
    data_off = struct.unpack_from("<I", d, 0x34)[0]
    count = struct.unpack_from("<H", d, 0x3E)[0]
    if data_off != table + count * 8:
        raise ValueError("索引表与数据区不相接 table=0x%X data=0x%X count=%d"
                         % (table, data_off, count))
    return table, count


def header_len(d, off, length, w, h, bpp, comp):
    """资源头长度 8 / 14 自适应；RAW 可由长度反推，RLE 只能看 +8 处的 0xCD 标记。"""
    if comp in (0, 5):
        diff = length - w * h * bpp
        if diff == 14:
            return 14
        if diff == 8:
            return 8
        if 8 < diff <= 14:
            return 8
        if diff > 14:
            return 14
        return -1
    if d[off + 8:off + 14] == b"\xcd" * 6:
        return 14
    return 8


def decode_raw(payload, w, h, depth, keep_alpha):
    n = w * h
    bpp = depth // 8
    body = bytearray(payload[: n * bpp])
    if len(body) < n * bpp:
        raise ValueError("payload 不足 %d/%d" % (len(body), n * bpp))
    if depth == 32:
        if not keep_alpha:
            body[3::4] = b"\xff" * n
            # 洋红色键必须按 4 字节像素边界判定：跨像素的字节模式会把
            # 上一像素的 A 与下一像素的 B/G 凑成假洋红，连带污染第三通道。
            for k in range(0, n * 4, 4):
                if body[k] == 0xFF and body[k + 1] == 0x00 and body[k + 2] == 0xFF:
                    body[k + 3] = 0
        return bytes(body)
    if depth == 24:
        out = bytearray(n * 4)
        for i in range(n):
            b, g, r = body[i * 3], body[i * 3 + 1], body[i * 3 + 2]
            out[i * 4:i * 4 + 4] = bytes((b, g, r, 255))
        return bytes(out)
    if depth == 16:
        out = bytearray(n * 4)
        for i in range(n):
            v = body[i * 2] | (body[i * 2 + 1] << 8)
            r = (v >> 11) & 0x1F
            r = (r << 3) | (r >> 2)
            g = (v >> 5) & 0x3F
            g = (g << 2) | (g >> 4)
            b = v & 0x1F
            b = (b << 3) | (b >> 2)
            out[i * 4:i * 4 + 4] = bytes((b, g, r, 255))
        return bytes(out)
    out = bytearray(n * 4)
    for i in range(n):
        v = body[i]
        out[i * 4:i * 4 + 4] = bytes((v, v, v, 255))
    return bytes(out)


def decode_rle(payload, w, h):
    """RK 非标准 RLE：token = B,G,R,count；count=0 终结，0xFF 后跟 u32 扩展，跨行线性连写。"""
    n = w * h
    buf = bytearray(n * 4)
    out = 0
    p = 0
    L = len(payload)
    while p + 4 <= L and out < n:
        b, g, r, c = payload[p], payload[p + 1], payload[p + 2], payload[p + 3]
        if c == 0xFF:
            if p + 8 > L:
                break
            extra = struct.unpack_from("<I", payload, p + 4)[0]
            run = min(255 + extra, n - out)
            p += 8
        elif c == 0:
            break
        else:
            run = min(c, n - out)
            p += 4
        buf[out * 4:(out + run) * 4] = bytes((b, g, r, 255)) * run
        out += run
    if out != n:
        raise ValueError("未铺满 %d/%d" % (out, n))
    for k in range(0, n * 4, 4):
        if buf[k] == 0xFF and buf[k + 1] == 0x00 and buf[k + 2] == 0xFF:
            buf[k + 3] = 0
    return bytes(buf)


def decode_one(d, table, i):
    length, off = struct.unpack_from("<ii", d, table + i * 8)
    w, h, depth, comp = struct.unpack_from("<hhhh", d, off)
    bpp = depth // 8
    head = header_len(d, off, length, w, h, bpp, comp)
    if head < 0:
        raise ValueError("头长无法判定 len=%d w=%d h=%d depth=%d comp=%d"
                         % (length, w, h, depth, comp))
    payload = d[off + head: off + length]
    if comp == 1:
        try:
            return decode_rle(payload, w, h), w, h, comp
        except ValueError:
            alt = 14 if head == 8 else 8
            return decode_rle(d[off + alt: off + length], w, h), w, h, comp
    return decode_raw(payload, w, h, depth, comp == 5), w, h, comp


def main(argv=None):
    ap = argparse.ArgumentParser(description="BMP0.BIN 独立参考解码器")
    ap.add_argument("src", help="BMP0.BIN 路径")
    ap.add_argument("--out", help="导出 MD5 清单到此文件")
    ap.add_argument("--verify", help="与此 MD5 清单逐条比对（Java 侧导出）")
    args = ap.parse_args(argv)

    d = read_full(args.src)
    table, count = parse_container(d)
    print("src=%s size=%d table=0x%X count=%d" % (args.src, len(d), table, count))

    lines = []
    ok = fail = 0
    for i in range(count):
        try:
            bgra, w, h, comp = decode_one(d, table, i)
            lines.append("%d %s %dx%d c%d\n"
                         % (i, hashlib.md5(bgra).hexdigest(), w, h, comp))
            ok += 1
        except Exception as e:                      # noqa: BLE001 - 参考实现不因单条中断
            lines.append("%d FAIL %s\n" % (i, e))
            fail += 1
    print("decode ok=%d fail=%d" % (ok, fail))

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fp:
            fp.writelines(lines)
        print("-> %s" % args.out)

    if args.verify:
        with open(args.verify, encoding="utf-8") as fp:
            java = [ln.split() for ln in fp if ln.strip()]
        ref = [ln.split() for ln in lines]
        diff = []
        for a, b in zip(ref, java):
            if len(a) < 2 or len(b) < 2:
                continue
            if a[1] != b[1]:
                diff.append((a[0], a[1], b[1]))
        print("verify total=%d/%d diff=%d" % (len(ref), len(java), len(diff)))
        for idx, x, y in diff[:20]:
            print("  #%s ref=%s java=%s" % (idx, x, y))
        return 0 if not diff and len(lines) == len(java) else 1
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
