"""把构建好的 dex 原位写回目标 APK。

原位替换：只改动目标条目的压缩数据与头部 CRC/长度，不重建整个 zip。
这样能保留原包中共享数据区（多个条目指向同一段数据）的布局，避免包体膨胀。

用法：
    python repack.py <目标.apk> <新dex> [--entry classes.dex] [--out 输出.apk]
"""

import argparse
import struct
import sys
import zlib
from pathlib import Path

def uleb(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

def find_local_header(data: bytes, name: str):
    """在中央目录里定位条目，返回 (cd_pos, lho, 字段字典)。"""
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0:
        raise SystemExit('不是有效的 zip：找不到 EOCD')
    cd_size, cd_off = struct.unpack_from('<II', data, eocd + 12)
    pos = cd_off
    target = name.encode()
    while pos < cd_off + cd_size and data[pos:pos + 4] == b'PK\x01\x02':
        f = struct.unpack_from('<IHHHHHHIIIHHHHHII', data, pos)
        name_len, extra_len, comment_len = f[10], f[11], f[12]
        entry_name = data[pos + 46:pos + 46 + name_len]
        if entry_name == target:
            return pos, f[16], dict(crc=f[7], comp=f[8], uncomp=f[9],
                                    method=f[4], flags=f[3], name_len=name_len)
        pos += 46 + name_len + extra_len + comment_len
    raise SystemExit(f'APK 中找不到条目 {name}')

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('apk')
    ap.add_argument('dex')
    ap.add_argument('--entry', default='classes.dex')
    ap.add_argument('--out')
    args = ap.parse_args()

    src = Path(args.apk)
    dst = Path(args.out) if args.out else src.with_name(src.stem + '-repacked.apk')
    data = bytearray(src.read_bytes())
    payload = Path(args.dex).read_bytes()

    cd_pos, lho, info = find_local_header(data, args.entry)
    if info['method'] != 8:
        raise SystemExit('目标条目不是 deflate 压缩，暂不支持')

    sig, _, _, _, _, _, _, _, _, nl, el = struct.unpack_from('<IHHHHHIIIHH', data, lho)
    if sig != 0x04034B50:
        raise SystemExit('局部头损坏')
    data_off = lho + 30 + nl + el
    old_comp = info['comp']

    comp = zlib.compressobj(6, zlib.DEFLATED, -15, 8, zlib.Z_DEFAULT_STRATEGY)
    stream = comp.compress(payload) + comp.flush()
    if len(stream) > old_comp:
        raise SystemExit(f'新条目压缩后更大（{len(stream)} > {old_comp}），需要重建 zip')
    stream += b'\x00' * (old_comp - len(stream))

    probe = zlib.decompressobj(-15)
    if probe.decompress(stream) != payload:
        raise SystemExit('压缩流自检失败')

    crc = zlib.crc32(payload) & 0xFFFFFFFF
    data[data_off:data_off + old_comp] = stream
    struct.pack_into('<III', data, lho + 14, crc, old_comp, len(payload))
    struct.pack_into('<III', data, cd_pos + 16, crc, old_comp, len(payload))

    dst.write_bytes(bytes(data))
    print(f'写入 {dst}（{len(data)} 字节，条目 {args.entry}: {len(payload)} 字节）')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
