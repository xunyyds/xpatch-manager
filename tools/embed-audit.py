#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
import time
import zlib

MANIFEST = "AndroidManifest.xml"
STAMP_ENTRY = "stamp-cert-sha256"
LEGACY_CONFIG = "xp/config.json"
MARKER = b"res_core"
NESTED_MAGIC = b"PK\x03\x04"

MAX_CONFIG_BYTES = 64 * 1024
MAX_CONFIG_PROBES = 512
MAX_DEX_BYTES = 2 * 1024 * 1024
MIN_EMBEDDED = 64 * 1024
EMBED_RATIO = 0.5
MAX_CANDIDATES = 64
CHUNK = 1024 * 1024

CONFIG_EXTS = frozenset({"json", "dat", "bin", "txt", "lst", ""})

LOGICAL_KEYS = (
    "cfg",
    "core",
    "hook_dex",
    "hook_so",
    "hook_so_x86_64",
    "dex89",
    "dex100",
    "dex101",
    "dex102",
    "file_provider",
    "source",
    "source_manifest",
    "pkg",
)

LEGACY_PREFIXES = (
    "xp/",
    "xpt/",
    "fpa/",
    "npatch/",
    "assets/npatch/",
    "lspatch/",
    "assets/lspatch/",
)

LEGACY_EXTRA_NAMES = frozenset({
    "assets/xposed_init",
    "META-INF/xposed/module.prop",
    "META-INF/xposed/java_init.list",
})

LEGACY_SOURCE_NAMES = (
    "xp/source.apk",
    "xp/o_app.apk",
    "fpa/source.apk",
    "assets/npatch/origin.apk",
    "lspatch/origin.apk",
    "assets/lspatch/origin.apk",
)

LEGACY_CORE_NAMES = ("xp/core.dex", "xpt/core.dex")

MODULE_CRASH_PERMISSION = "io.xpatch.permission.MODULE_CRASH"

ZIP_LOCAL = 0x04034B50
ZIP_CENTRAL = 0x02014B50
ZIP_EOCD = 0x06054B50
ZIP64_EOCD = 0x06064B50
ZIP64_LOCATOR = 0x07064B50

AXML_MAGIC = 0x0003
AXML_STRING_POOL = 0x0001
AXML_START_ELEMENT = 0x0102
AXML_UTF8_FLAG = 0x0100
TYPE_STRING = 0x03


class AuditError(Exception):
    pass


def format_size(value: int) -> str:
    if value >= 1024 * 1024 * 1024:
        return "%.2f GB" % (value / 1024.0 / 1024.0 / 1024.0)
    if value >= 1024 * 1024:
        return "%.2f MB" % (value / 1024.0 / 1024.0)
    if value >= 1024:
        return "%.1f KB" % (value / 1024.0)
    return "%d B" % value


def dos_text(dos_date: int, dos_time: int) -> str:
    if dos_date <= 0:
        return "(无)"
    year = 1980 + ((dos_date >> 9) & 0x7F)
    month = (dos_date >> 5) & 0x0F
    day = dos_date & 0x1F
    hour = (dos_time >> 11) & 0x1F
    minute = (dos_time >> 5) & 0x3F
    second = (dos_time & 0x1F) * 2
    return "%04d-%02d-%02d %02d:%02d:%02d" % (year, month, day, hour, minute, second)


def is_dos_epoch(dos_date: int, dos_time: int) -> bool:
    return (dos_date == 0x0021 and dos_time == 0) or dos_date == 0


def method_name(method: int) -> str:
    if method == 0:
        return "stored"
    if method == 8:
        return "deflate"
    if method == 12:
        return "bzip2"
    if method == 14:
        return "lzma"
    if method == 93:
        return "zstd"
    return "method=%d" % method


def looks_like_boot_dex(name: str) -> bool:
    if not name.startswith("classes") or not name.endswith(".dex"):
        return False
    middle = name[len("classes"):-len(".dex")]
    return middle == "" or middle.isdigit()


def is_legacy_injected(name: str) -> bool:
    if name in LEGACY_EXTRA_NAMES:
        return True
    for prefix in LEGACY_PREFIXES:
        if name.startswith(prefix):
            return True
    return False


def legacy_rule(name: str) -> str:
    if name in LEGACY_EXTRA_NAMES:
        return "foreign:xposed"
    if name.startswith("xp/") or name.startswith("xpt/"):
        return "legacy:xpatch"
    if name.startswith("fpa/"):
        return "foreign:fpa"
    if name.startswith("npatch/") or name.startswith("assets/npatch/"):
        return "foreign:npatch"
    if name.startswith("lspatch/") or name.startswith("assets/lspatch/"):
        return "foreign:lspatch"
    return ""


class Raw:
    __slots__ = ("handle", "base", "size", "owned")

    def __init__(self, handle, base: int, size: int, owned: bool = False):
        self.handle = handle
        self.base = base
        self.size = size
        self.owned = owned

    def read(self, offset: int, count: int) -> bytes:
        if count <= 0 or offset < 0 or offset >= self.size:
            return b""
        count = min(count, self.size - offset)
        self.handle.seek(self.base + offset)
        return self.handle.read(count)

    def read_all(self, offset: int, count: int) -> bytes:
        parts = []
        got = 0
        while got < count:
            data = self.read(offset + got, min(CHUNK, count - got))
            if not data:
                break
            parts.append(data)
            got += len(data)
        return b"".join(parts)

    def close(self) -> None:
        if self.owned:
            try:
                self.handle.close()
            except OSError:
                pass


class Entry:
    __slots__ = (
        "index",
        "name",
        "method",
        "flags",
        "crc",
        "compressed",
        "size",
        "offset",
        "dos_time",
        "dos_date",
        "data_start",
    )

    def __init__(self, index, name, method, flags, crc, compressed, size, offset, dos_time, dos_date):
        self.index = index
        self.name = name
        self.method = method
        self.flags = flags
        self.crc = crc
        self.compressed = compressed
        self.size = size
        self.offset = offset
        self.dos_time = dos_time
        self.dos_date = dos_date
        self.data_start = -1

    def stamp(self) -> str:
        return dos_text(self.dos_date, self.dos_time)

    def as_json(self) -> dict:
        return {
            "name": self.name,
            "size": self.size,
            "compressed": self.compressed,
            "method": method_name(self.method),
            "crc": self.crc,
            "dos": self.stamp(),
            "index": self.index,
        }


def parse_extra_zip64(body: bytes, needed: dict) -> dict:
    pos = 0
    result = {}
    for key in ("size", "compressed", "offset", "disk"):
        if not needed.get(key):
            continue
        if pos + 8 > len(body):
            break
        result[key] = struct.unpack_from("<Q", body, pos)[0]
        pos += 8
    return result


def find_eocd(raw: Raw) -> dict:
    if raw.size < 22:
        raise AuditError("文件太小，不是 zip（%d B）" % raw.size)
    window = min(raw.size, 0xFFFF + 22 + 1024)
    data = raw.read_all(raw.size - window, window)
    pos = data.rfind(b"PK\x05\x06")
    if pos < 0:
        raise AuditError("找不到 EOCD：不是 zip 或被截断")
    if pos + 22 > len(data):
        raise AuditError("EOCD 被截断")
    total_entries, cd_size, cd_offset, comment_len = struct.unpack_from("<HIIH", data, pos + 10)
    eocd_offset = raw.size - window + pos
    info = {
        "eocd_offset": eocd_offset,
        "count": total_entries,
        "cd_size": cd_size,
        "cd_offset": cd_offset,
        "comment_len": comment_len,
        "zip64": False,
    }
    if total_entries == 0xFFFF or cd_offset == 0xFFFFFFFF or cd_size == 0xFFFFFFFF:
        locator_offset = eocd_offset - 20
        locator = raw.read_all(locator_offset, 20)
        if len(locator) != 20 or struct.unpack_from("<I", locator, 0)[0] != ZIP64_LOCATOR:
            raise AuditError("ZIP64 locator 缺失，无法解析中央目录")
        zip64_offset = struct.unpack_from("<Q", locator, 8)[0]
        record = raw.read_all(zip64_offset, 56)
        if len(record) != 56 or struct.unpack_from("<I", record, 0)[0] != ZIP64_EOCD:
            raise AuditError("ZIP64 EOCD 记录损坏")
        info["count"] = struct.unpack_from("<Q", record, 32)[0]
        info["cd_size"] = struct.unpack_from("<Q", record, 40)[0]
        info["cd_offset"] = struct.unpack_from("<Q", record, 48)[0]
        info["zip64"] = True
    return info


class ZipReader:
    def __init__(self, raw: Raw, label: str = ""):
        self.raw = raw
        self.label = label
        self.eocd = find_eocd(raw)
        self.entries: list[Entry] = []
        self.by_name: dict[str, Entry] = {}
        self.trailing = 0
        self._load_central()

    def _load_central(self) -> None:
        cd_offset = self.eocd["cd_offset"]
        cd_size = self.eocd["cd_size"]
        if cd_offset + cd_size > self.raw.size:
            raise AuditError(
                "%s中央目录越界：offset=%d size=%d 文件=%d"
                % (self.label, cd_offset, cd_size, self.raw.size)
            )
        blob = self.raw.read_all(cd_offset, cd_size)
        if len(blob) != cd_size:
            raise AuditError("%s中央目录读取不完整" % self.label)
        pos = 0
        index = 0
        while pos + 46 <= len(blob):
            (sig, _made, _needed, flags, method, dos_time, dos_date, crc, compressed, size,
             name_len, extra_len, comment_len, _disk, _iattr, _eattr, local_offset) = struct.unpack_from(
                "<IHHHHHHIIIHHHHHII", blob, pos
            )
            if sig != ZIP_CENTRAL:
                break
            raw_name = blob[pos + 46:pos + 46 + name_len]
            name = raw_name.decode("utf-8", "replace")
            if 0xFFFFFFFF in (compressed, size, local_offset):
                extra_pos = pos + 46 + name_len
                extra_end = extra_pos + extra_len
                while extra_pos + 4 <= extra_end:
                    header_id, header_size = struct.unpack_from("<HH", blob, extra_pos)
                    body = blob[extra_pos + 4:extra_pos + 4 + header_size]
                    if header_id == 1:
                        values = parse_extra_zip64(body, {
                            "size": size == 0xFFFFFFFF,
                            "compressed": compressed == 0xFFFFFFFF,
                            "offset": local_offset == 0xFFFFFFFF,
                        })
                        size = values.get("size", size)
                        compressed = values.get("compressed", compressed)
                        local_offset = values.get("offset", local_offset)
                        break
                    extra_pos += 4 + header_size
            entry = Entry(
                index, name, method, flags, crc, compressed, size, local_offset, dos_time, dos_date
            )
            self.entries.append(entry)
            if name not in self.by_name:
                self.by_name[name] = entry
            index += 1
            pos += 46 + name_len + extra_len + comment_len
        if not self.entries:
            raise AuditError("%s中央目录里没有条目" % self.label)
        self.trailing = self.raw.size - (
            self.eocd["eocd_offset"] + 22 + self.eocd["comment_len"]
        )
        if self.trailing < 0:
            self.trailing = 0

    def data_start(self, entry: Entry) -> int:
        if entry.data_start >= 0:
            return entry.data_start
        header = self.raw.read_all(entry.offset, 30)
        if len(header) != 30 or struct.unpack_from("<I", header, 0)[0] != ZIP_LOCAL:
            raise AuditError("%s条目的本地头损坏：%s" % (self.label, entry.name))
        name_len, extra_len = struct.unpack_from("<HH", header, 26)
        entry.data_start = entry.offset + 30 + name_len + extra_len
        return entry.data_start

    def iter_chunks(self, entry: Entry):
        start = self.data_start(entry)
        if entry.method == 0:
            pos = 0
            while pos < entry.compressed:
                data = self.raw.read(start + pos, min(CHUNK, entry.compressed - pos))
                if not data:
                    break
                pos += len(data)
                yield data
            return
        if entry.method != 8:
            raise AuditError(
                "%s不支持压缩方式 %s：%s" % (self.label, method_name(entry.method), entry.name)
            )
        inflater = zlib.decompressobj(-15)
        pos = 0
        while pos < entry.compressed:
            data = self.raw.read(start + pos, min(CHUNK, entry.compressed - pos))
            if not data:
                break
            pos += len(data)
            out = inflater.decompress(data)
            if out:
                yield out
        tail = inflater.flush()
        if tail:
            yield tail

    def read_bytes(self, entry: Entry, limit: int | None = None) -> bytes:
        if limit is not None and entry.size > limit:
            raise AuditError(
                "%s条目超过上限 %d B：%s (%d B)" % (self.label, limit, entry.name, entry.size)
            )
        parts = []
        got = 0
        for chunk in self.iter_chunks(entry):
            parts.append(chunk)
            got += len(chunk)
            if limit is not None and got > limit:
                raise AuditError("%s条目实际解压超过上限：%s" % (self.label, entry.name))
        return b"".join(parts)

    def crc_of(self, entry: Entry) -> int:
        value = 0
        for chunk in self.iter_chunks(entry):
            value = zlib.crc32(chunk, value)
        return value & 0xFFFFFFFF

    def region(self, entry: Entry) -> Raw:
        start = self.data_start(entry)
        return Raw(self.raw.handle, self.raw.base + start, entry.compressed)

    def close(self) -> None:
        self.raw.close()


def read_len8(raw: bytes, pos: int):
    value = raw[pos]
    pos += 1
    if value & 0x80:
        value = ((value & 0x7F) << 8) | raw[pos]
        pos += 1
    return value, pos


def decode_string_pool(blob: bytes, pos: int, header_size: int, chunk_size: int) -> list[str]:
    count, _styles, flags, strings_start, _styles_start = struct.unpack_from("<IIIII", blob, pos + 8)
    if count > 200000:
        raise AuditError("AXML 字符串池过大：%d" % count)
    offsets = struct.unpack_from("<%dI" % count, blob, pos + header_size)
    base = pos + strings_start
    end = pos + chunk_size
    utf8 = (flags & AXML_UTF8_FLAG) != 0
    result = []
    for offset in offsets:
        cursor = base + offset
        if cursor >= end:
            result.append("")
            continue
        try:
            if utf8:
                _chars, cursor = read_len8(blob, cursor)
                byte_len, cursor = read_len8(blob, cursor)
                result.append(blob[cursor:cursor + byte_len].decode("utf-8", "replace"))
            else:
                length = struct.unpack_from("<H", blob, cursor)[0]
                cursor += 2
                if length & 0x8000:
                    length = ((length & 0x7FFF) << 16) | struct.unpack_from("<H", blob, cursor)[0]
                    cursor += 2
                result.append(blob[cursor:cursor + length * 2].decode("utf-16-le", "replace"))
        except (IndexError, struct.error):
            result.append("")
    return result


def string_at(pool: list[str], index: int) -> str:
    if index < 0 or index >= len(pool):
        return ""
    return pool[index]


def decode_start_element(blob: bytes, pos: int, header_size: int, pool: list[str]) -> tuple[str, dict]:
    ext = pos + header_size
    ns_index, name_index, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", blob, ext)
    attributes = {}
    for index in range(attr_count):
        base = ext + attr_start + index * attr_size
        ns, name, raw_value, _vsize, _res0, dtype, data = struct.unpack_from("<IIIHBBI", blob, base)
        key = string_at(pool, name)
        if ns != 0xFFFFFFFF:
            ns_name = string_at(pool, ns)
            if ns_name and key:
                key = ns_name + ":" + key
        if raw_value != 0xFFFFFFFF:
            value = string_at(pool, raw_value)
        elif dtype == TYPE_STRING:
            value = string_at(pool, data)
        elif dtype == 0x12:
            value = "true" if data else "false"
        else:
            value = str(data)
        attributes[key] = value
    return string_at(pool, name_index), attributes


def parse_axml(blob: bytes) -> tuple[list[str], list[tuple[str, dict]]]:
    if len(blob) < 8:
        raise AuditError("AXML 太短")
    magic, header_size, _total = struct.unpack_from("<HHI", blob, 0)
    if magic != AXML_MAGIC:
        raise AuditError("不是 AXML")
    pool: list[str] = []
    elements: list[tuple[str, dict]] = []
    pos = header_size
    while pos + 8 <= len(blob):
        chunk_type, chunk_header, chunk_size = struct.unpack_from("<HHI", blob, pos)
        if chunk_size < 8 or pos + chunk_size > len(blob):
            break
        if chunk_type == AXML_STRING_POOL:
            pool = decode_string_pool(blob, pos, chunk_header, chunk_size)
        elif chunk_type == AXML_START_ELEMENT:
            if pool:
                elements.append(decode_start_element(blob, pos, chunk_header, pool))
        pos += chunk_size
    return pool, elements


class Manifest:
    def __init__(self, blob: bytes):
        self.raw = blob
        self.strings: list[str] = []
        self.elements: list[tuple[str, dict]] = []
        self.axml = True
        try:
            self.strings, self.elements = parse_axml(blob)
        except (AuditError, struct.error, IndexError):
            self.axml = False

    def manifest_attributes(self) -> dict:
        for name, attributes in self.elements:
            if name == "manifest":
                return attributes
        return {}

    def package(self) -> str | None:
        attributes = self.manifest_attributes()
        value = attributes.get("package")
        if value:
            return value
        if not self.axml:
            text = self.raw.decode("utf-8", "replace")
            marker = 'package="'
            index = text.find(marker)
            if index >= 0:
                end = text.find('"', index + len(marker))
                if end > index:
                    return text[index + len(marker):end]
        return None

    def xpatch_strings(self, needle: str = "xpatch") -> list[str]:
        found = []
        seen = set()
        for value in self.strings:
            if needle in value and value not in seen:
                seen.add(value)
                found.append(value)
        return found

    def raw_utf16_hits(self, needle: str) -> int:
        return self.raw.count(needle.encode("utf-16-le"))

    def raw_utf8_hits(self, needle: str) -> int:
        return self.raw.count(needle.encode("utf-8"))


def config_extension(name: str) -> str:
    dot = name.rfind(".")
    slash = name.rfind("/")
    if dot <= slash:
        return ""
    return name[dot + 1:].lower()


def looks_like_config(name: str, size: int) -> bool:
    if not name or name.endswith("/"):
        return False
    if size <= 0 or size > MAX_CONFIG_BYTES:
        return False
    return config_extension(name) in CONFIG_EXTS


def is_layout_config_text(text: str) -> bool:
    if not text or len(text.encode("utf-8", "ignore")) > MAX_CONFIG_BYTES:
        return False
    if not text.lstrip().startswith("{"):
        return False
    return '"res_cfg"' in text


def parse_layout(text: str) -> dict | None:
    try:
        obj = json.loads(text)
    except (ValueError, UnicodeDecodeError):
        return None
    if not isinstance(obj, dict):
        return None
    config_entry = obj.get("res_cfg")
    if not isinstance(config_entry, str) or not config_entry.strip():
        return None
    entries = {"cfg": config_entry}
    for key in LOGICAL_KEYS[1:]:
        value = obj.get("res_" + key)
        if isinstance(value, str) and value.strip():
            entries[key] = value
    dir_value = obj.get("res_dir")
    if not isinstance(dir_value, str) or not dir_value.strip():
        dir_value = config_entry.rsplit("/", 1)[0] if "/" in config_entry else ""
    factory = obj.get("res_factory")
    hex8 = obj.get("res_hex8")
    return {
        "family": "layout",
        "config_entry": config_entry,
        "dir": dir_value,
        "entries": entries,
        "boot_dex": obj.get("res_boot_dex") if isinstance(obj.get("res_boot_dex"), str) else "",
        "factory": factory if isinstance(factory, str) else "",
        "provider": obj.get("res_provider") if isinstance(obj.get("res_provider"), str) else "",
        "hex8": hex8 if isinstance(hex8, str) else "",
        "runtime_class": obj.get("res_runtime_class") if isinstance(obj.get("res_runtime_class"), str) else "",
        "v": obj.get("res_v"),
        "enc": obj.get("res_enc"),
        "raw_keys": sorted(obj.keys()),
        "raw": obj,
    }


def legacy_layout(zip_reader: ZipReader) -> dict:
    entries = {"cfg": LEGACY_CONFIG}
    mapping = {
        "core": ("xp/core.dex", "xpt/core.dex"),
        "hook_dex": ("xp/hook_core.dex",),
        "hook_so": ("xp/hook_core.so",),
        "hook_so_x86_64": ("xp/hook_core_x86_64.so",),
        "dex89": ("xp/xp89.dex",),
        "dex100": ("xp/xp100.dex",),
        "dex101": ("xp/xp101.dex",),
        "dex102": ("xp/xp102.dex",),
        "file_provider": ("xp/file_provider", "xp/fp"),
        "pkg": ("xp/pkg",),
        "source_manifest": ("xp/source.manifest",),
    }
    for key, candidates in mapping.items():
        for name in candidates:
            if name in zip_reader.by_name:
                entries[key] = name
                break
    for name in LEGACY_SOURCE_NAMES:
        if name in zip_reader.by_name:
            entries["source"] = name
            break
    core = entries.get("core", "")
    return {
        "family": "legacy",
        "config_entry": LEGACY_CONFIG,
        "dir": "xp/",
        "entries": entries,
        "boot_dex": "",
        "factory": "",
        "provider": "",
        "hex8": "",
        "runtime_class": "",
        "v": None,
        "enc": None,
        "raw_keys": [],
        "raw": None,
        "core": core,
    }


def scan_dex_markers(zip_reader: ZipReader) -> dict:
    scanned = 0
    scanned_bytes = 0
    hits = []
    for entry in zip_reader.entries:
        if not looks_like_boot_dex(entry.name):
            continue
        if entry.size <= 0 or entry.size > MAX_DEX_BYTES:
            continue
        scanned += 1
        scanned_bytes += entry.size
        try:
            blob = zip_reader.read_bytes(entry, MAX_DEX_BYTES)
        except AuditError:
            continue
        if MARKER in blob:
            hits.append({"name": entry.name, "size": entry.size})
    return {"scanned": scanned, "scanned_bytes": scanned_bytes, "hits": hits}


def discover(zip_reader: ZipReader, max_probes: int) -> dict:
    steps = []
    layout = None
    mode = "none"
    config_entry = None
    config_text = None
    config_size = 0

    legacy_entry = zip_reader.by_name.get(LEGACY_CONFIG)
    if legacy_entry is not None:
        steps.append({
            "step": "xp/config.json",
            "hit": True,
            "detail": "%d B" % legacy_entry.size,
        })
        try:
            text = zip_reader.read_bytes(legacy_entry, 1024 * 1024).decode("utf-8", "replace")
        except AuditError:
            text = ""
        parsed = parse_layout(text)
        if parsed is not None:
            mode = "layout"
            config_entry = LEGACY_CONFIG
            config_text = text
            config_size = legacy_entry.size
            layout = parsed
        else:
            mode = "legacy"
            config_entry = LEGACY_CONFIG
            config_text = text
            config_size = legacy_entry.size
    else:
        steps.append({"step": "xp/config.json", "hit": False, "detail": "无该条目"})

    markers = scan_dex_markers(zip_reader)
    dex_detail = "扫描 %d 个 ≤2MB 的 classes*.dex（%s）" % (
        markers["scanned"], format_size(markers["scanned_bytes"])
    )
    if markers["hits"]:
        dex_detail += "，ASCII res_core 命中 " + ", ".join(
            "%s (%s)" % (item["name"], format_size(item["size"])) for item in markers["hits"]
        )
    steps.append({
        "step": "classes*.dex res_core 标记",
        "hit": bool(markers["hits"]),
        "detail": dex_detail,
    })

    probes = 0
    candidates = 0
    probe_hit = None
    if mode == "none" and markers["hits"]:
        for entry in reversed(zip_reader.entries):
            if not looks_like_config(entry.name, entry.size):
                continue
            candidates += 1
            if probes >= max_probes:
                continue
            probes += 1
            try:
                blob = zip_reader.read_bytes(entry, MAX_CONFIG_BYTES)
            except AuditError:
                continue
            text = blob.decode("utf-8", "replace")
            if is_layout_config_text(text):
                probe_hit = entry
                parsed = parse_layout(text)
                if parsed is not None:
                    mode = "layout"
                    config_entry = entry.name
                    config_text = text
                    config_size = entry.size
                    layout = parsed
                break
        steps.append({
            "step": "反向小条目探测",
            "hit": probe_hit is not None,
            "detail": "候选 %d 个，探测 %d 次（上限 %d）%s" % (
                candidates,
                probes,
                max_probes,
                "" if probe_hit is None else "，命中 " + probe_hit.name,
            ),
        })
    else:
        steps.append({
            "step": "反向小条目探测",
            "hit": False,
            "detail": "跳过（无 res_core 标记或已有 config）",
        })

    if mode == "none" and legacy_entry is not None:
        mode = "legacy"

    if mode == "legacy":
        layout = legacy_layout(zip_reader)

    if mode == "layout" and layout is not None:
        for key in LOGICAL_KEYS:
            name = layout["entries"].get(key)
            if name and name not in zip_reader.by_name:
                steps.append({
                    "step": "res_" + key + " 指向条目",
                    "hit": False,
                    "detail": name + " 不在条目表里",
                })
    return {
        "mode": mode,
        "config_entry": config_entry,
        "config_size": config_size,
        "config_text": config_text,
        "layout": layout,
        "steps": steps,
        "markers": markers,
        "probes": probes,
        "candidates": candidates,
    }


def build_injected(zip_reader: ZipReader, layout: dict | None, markers: dict) -> dict:
    injected: dict[str, str] = {}
    boot_names = [item["name"] for item in markers["hits"]]
    if layout is not None:
        family = layout.get("family", "layout")
        for key in LOGICAL_KEYS:
            name = layout["entries"].get(key)
            if name:
                injected.setdefault(name, family + ":" + key)
    boot_dex = ""
    declared = layout["boot_dex"] if layout is not None else ""
    if declared and declared in zip_reader.by_name:
        boot_dex = declared
    elif boot_names:
        boot_dex = boot_names[0]
    if boot_dex:
        injected.setdefault(boot_dex, "boot_dex")
    legacy_hits = []
    xpatch_legacy = []
    for entry in zip_reader.entries:
        rule = legacy_rule(entry.name)
        if not rule:
            continue
        legacy_hits.append(entry.name)
        if rule == "legacy:xpatch":
            xpatch_legacy.append(entry.name)
        injected.setdefault(entry.name, rule)
    return {
        "map": injected,
        "boot_dex": boot_dex,
        "boot_candidates": boot_names,
        "legacy_hits": legacy_hits,
        "xpatch_legacy": xpatch_legacy,
    }


def pick_sources(zip_reader: ZipReader, layout: dict | None, injected: dict) -> dict:
    preferred = ""
    preferred_reason = ""
    if layout is not None:
        name = layout["entries"].get("source")
        if name and name in zip_reader.by_name:
            preferred = name
            preferred_reason = "config res_source" if layout["config_entry"] != LEGACY_CONFIG else "config xp/source.apk"
    if not preferred:
        for name in LEGACY_SOURCE_NAMES:
            if name in zip_reader.by_name:
                preferred = name
                preferred_reason = "旧固定名"
                break
    declared = []
    for entry in zip_reader.entries:
        if entry.name.lower().endswith(".apk") and entry.size >= MIN_EMBEDDED:
            declared.append(entry)
    others = [entry.name for entry in declared if entry.name != preferred]
    return {
        "preferred": preferred,
        "preferred_reason": preferred_reason,
        "declared_apk": [entry.as_json() for entry in declared],
        "other_apk": others,
    }


def header_magic_matches(zip_reader: ZipReader, entry: Entry) -> bool:
    try:
        start = zip_reader.data_start(entry)
    except AuditError:
        return False
    return zip_reader.raw.read(start, 4) == NESTED_MAGIC


def collect_candidates(zip_reader: ZipReader, sources: dict, max_candidates: int) -> list[tuple[str, str]]:
    ordered: list[tuple[str, str]] = []
    seen = set()
    preferred = sources["preferred"]
    if preferred:
        ordered.append((preferred, sources["preferred_reason"]))
        seen.add(preferred)
    for entry in zip_reader.entries:
        if entry.name in seen:
            continue
        if entry.name.lower().endswith(".apk") and entry.size >= MIN_EMBEDDED:
            ordered.append((entry.name, "名字以 .apk 结尾且 ≥64KB"))
            seen.add(entry.name)
    for entry in zip_reader.entries:
        if entry.name in seen:
            continue
        if entry.size < MIN_EMBEDDED:
            continue
        if not header_magic_matches(zip_reader, entry):
            continue
        ordered.append((entry.name, "≥64KB 且前 4 字节 PK\\x03\\x04"))
        seen.add(entry.name)
        if len(ordered) >= max_candidates:
            break
    return ordered[:max_candidates]


def exclude_for_v3(zip_reader: ZipReader, injected: dict, candidate: str) -> set[str]:
    excluded = {MANIFEST, STAMP_ENTRY, candidate}
    excluded.update(injected.keys())
    for entry in zip_reader.entries:
        if entry.name.startswith("META-INF/"):
            excluded.add(entry.name)
    return excluded


def verify_source(
    zip_reader: ZipReader,
    entry: Entry,
    outer_package: str | None,
    injected: dict,
    mode: str,
    work_dir: str,
) -> dict:
    result = {
        "name": entry.name,
        "size": entry.size,
        "compressed": entry.compressed,
        "method": method_name(entry.method),
        "crc": entry.crc,
        "v1": False,
        "v2": False,
        "v3": False,
        "v4": False,
        "package": None,
        "entries": 0,
        "detail": "",
        "v3_checked": 0,
        "v3_hits": 0,
        "v3_excluded": 0,
        "v3_excluded_present": 0,
        "v3_missing_count": 0,
        "v3_mismatch_count": 0,
        "v3_missing_sample": [],
        "v3_mismatch_sample": [],
        "v3_inferred_dex": [],
        "v3_ignored_dex": [],
        "v3_effective": False,
        "v3_effective_missing": 0,
        "v3_effective_mismatch": 0,
        "v3_skipped": False,
        "inner_only": 0,
        "inner_manifest": False,
        "inner_dex": 0,
        "extracted": "",
    }
    outer_size = zip_reader.raw.size
    result["v4_ratio"] = round(entry.size / outer_size, 6) if outer_size else 0.0
    result["v4"] = entry.size >= outer_size * EMBED_RATIO
    nested_raw = None
    temp_path = ""
    try:
        if entry.method == 0:
            nested_raw = zip_reader.region(entry)
        else:
            temp_path = os.path.join(work_dir, "embedded-source.apk")
            with open(temp_path, "wb") as handle:
                for chunk in zip_reader.iter_chunks(entry):
                    handle.write(chunk)
            handle = open(temp_path, "rb")
            nested_raw = Raw(handle, 0, os.path.getsize(temp_path), owned=True)
            result["extracted"] = temp_path
        nested = ZipReader(nested_raw, label="内嵌包 ")
        result["entries"] = len(nested.entries)
        result["inner_dex"] = sum(1 for item in nested.entries if looks_like_boot_dex(item.name))
        result["inner_manifest"] = MANIFEST in nested.by_name
        result["v1"] = result["inner_manifest"] and result["inner_dex"] > 0
        if result["inner_manifest"]:
            try:
                inner_manifest = Manifest(nested.read_bytes(nested.by_name[MANIFEST], 8 * 1024 * 1024))
                result["package"] = inner_manifest.package()
            except AuditError:
                result["package"] = None
        result["v2"] = bool(outer_package) and result["package"] == outer_package
        result["v3_skipped"] = False
        excluded = exclude_for_v3(zip_reader, injected, entry.name)
        checked = 0
        missing_all = []
        mismatch_all = []
        mismatch_sample = []
        ignored_dex = []
        outer_names = set()
        for outer_entry in zip_reader.entries:
            outer_names.add(outer_entry.name)
            if outer_entry.name in excluded:
                continue
            checked += 1
            inner_entry = nested.by_name.get(outer_entry.name)
            if inner_entry is None:
                if looks_like_boot_dex(outer_entry.name):
                    ignored_dex.append(outer_entry.name)
                    continue
                missing_all.append(outer_entry.name)
                continue
            same = inner_entry.size == outer_entry.size
            if mode != "fast":
                same = same and inner_entry.crc == outer_entry.crc
            if same:
                continue
            if looks_like_boot_dex(outer_entry.name):
                ignored_dex.append(outer_entry.name)
                continue
            mismatch_all.append(outer_entry.name)
            if len(mismatch_sample) < 20:
                mismatch_sample.append({
                    "name": outer_entry.name,
                    "product": {"size": outer_entry.size, "crc": outer_entry.crc},
                    "inner": {"size": inner_entry.size, "crc": inner_entry.crc},
                })
        inner_only = sum(1 for name in nested.by_name if name not in outer_names)
        ignored = sorted(set(ignored_dex))
        result["v3_checked"] = checked
        result["v3_hits"] = checked - len(missing_all) - len(mismatch_all)
        result["v3_excluded"] = len(excluded)
        result["v3_excluded_present"] = sum(1 for name in excluded if name in zip_reader.by_name)
        result["v3_ignored_dex"] = ignored
        result["v3_inferred_dex"] = ignored
        result["v3_missing_count"] = len(missing_all)
        result["v3_mismatch_count"] = len(mismatch_all)
        result["v3_missing_sample"] = missing_all[:20]
        result["v3_mismatch_sample"] = mismatch_sample
        result["v3"] = checked > 0 and not missing_all and not mismatch_all
        result["v3_effective_missing"] = len(missing_all)
        result["v3_effective_mismatch"] = len(mismatch_all)
        result["v3_effective"] = result["v3"]
        result["inner_only"] = inner_only
        result["detail"] = (
            "checked %d / missing %d / mismatch %d（排除 %d 条，其中命中 %d 条）"
            "；classes*.dex 按注入跳过 %d 条%s"
            % (
                checked,
                len(missing_all),
                len(mismatch_all),
                len(excluded),
                result["v3_excluded_present"],
                len(ignored),
                "" if not ignored else "（" + ", ".join(ignored[:5]) + "）",
            )
        )
        nested.close()
    except (AuditError, OSError, struct.error, zlib.error) as error:
        result["detail"] = "%s: %s" % (type(error).__name__, error)
    finally:
        if nested_raw is not None:
            nested_raw.close()
        if temp_path and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass
    return result


def compare_host(
    zip_reader: ZipReader,
    source_zip: ZipReader,
    injected: dict,
    mode: str,
) -> dict:
    excluded = set(injected.keys())
    excluded.add(MANIFEST)
    excluded.add(STAMP_ENTRY)
    for entry in zip_reader.entries:
        if entry.name.startswith("META-INF/"):
            excluded.add(entry.name)
    for entry in source_zip.entries:
        if entry.name.startswith("META-INF/"):
            excluded.add(entry.name)
    checked = 0
    changed = []
    added = []
    for entry in zip_reader.entries:
        if entry.name in excluded:
            continue
        checked += 1
        other = source_zip.by_name.get(entry.name)
        if other is None:
            if len(added) < 50:
                added.append(entry.name)
            continue
        same = other.size == entry.size
        if mode != "fast":
            same = same and other.crc == entry.crc
        if not same and len(changed) < 50:
            changed.append({
                "name": entry.name,
                "product": {"size": entry.size, "crc": entry.crc},
                "source": {"size": other.size, "crc": other.crc},
            })
    removed = []
    for entry in source_zip.entries:
        if entry.name in excluded:
            continue
        if entry.name not in zip_reader.by_name and len(removed) < 50:
            removed.append(entry.name)
    checked_count = checked
    changed_count = 0
    added_count = 0
    for entry in zip_reader.entries:
        if entry.name in excluded:
            continue
        other = source_zip.by_name.get(entry.name)
        if other is None:
            added_count += 1
            continue
        same = other.size == entry.size
        if mode != "fast":
            same = same and other.crc == entry.crc
        if not same:
            changed_count += 1
    removed_count = sum(
        1 for entry in source_zip.entries if entry.name not in excluded and entry.name not in zip_reader.by_name
    )
    return {
        "source": source_zip.raw.size,
        "source_entries": len(source_zip.entries),
        "checked": checked_count,
        "changed": changed_count,
        "added": added_count,
        "removed": removed_count,
        "changed_sample": changed,
        "added_sample": added,
        "removed_sample": removed,
        "excluded": len(excluded),
    }


def core_evidence(zip_reader: ZipReader, layout: dict | None) -> dict:
    info = {"name": "", "size": 0, "sha256": "", "ok": False}
    core_name = ""
    if layout is not None:
        core_name = layout["entries"].get("core", "")
    if not core_name:
        for name in LEGACY_CORE_NAMES:
            if name in zip_reader.by_name:
                core_name = name
                break
    if not core_name or core_name not in zip_reader.by_name:
        return info
    entry = zip_reader.by_name[core_name]
    try:
        blob = zip_reader.read_bytes(entry, 64 * 1024 * 1024)
    except AuditError:
        return info
    info["name"] = core_name
    info["size"] = len(blob)
    info["sha256"] = hashlib.sha256(blob).hexdigest()
    info["ok"] = True
    return info


def verify_declared_crc(zip_reader: ZipReader, injected: dict) -> dict:
    checked = 0
    bad = []
    for entry in zip_reader.entries:
        if entry.name in injected:
            continue
        if entry.size > 8 * 1024 * 1024:
            continue
        try:
            actual = zip_reader.crc_of(entry)
        except AuditError:
            continue
        checked += 1
        if actual != entry.crc and len(bad) < 20:
            bad.append({"name": entry.name, "declared": entry.crc, "actual": actual})
    return {"checked": checked, "bad": bad}


def tail_info(zip_reader: ZipReader) -> dict:
    last_end = 0
    after_cd = []
    for entry in zip_reader.entries:
        try:
            end = zip_reader.data_start(entry) + entry.compressed
        except AuditError:
            continue
        if end > last_end:
            last_end = end
        if end > zip_reader.eocd["cd_offset"]:
            after_cd.append(entry.name)
    gap = zip_reader.eocd["cd_offset"] - last_end
    return {
        "trailing_bytes": zip_reader.trailing,
        "gap_before_cd": gap if gap > 0 else 0,
        "data_after_cd": after_cd,
        "tail_append": bool(after_cd),
    }


def audit(args) -> dict:
    started = time.time()
    apk_path = os.path.abspath(args.apk)
    if not os.path.isfile(apk_path):
        raise AuditError("找不到 APK：%s" % apk_path)
    mode = "fast" if args.fast else ("deep" if args.deep else "declared")
    handle = open(apk_path, "rb")
    raw = Raw(handle, 0, os.path.getsize(apk_path), owned=True)
    checks = []
    errors = []
    try:
        zip_reader = ZipReader(raw, label="外层 ")
        manifest_entry = zip_reader.by_name.get(MANIFEST)
        if manifest_entry is None:
            raise AuditError("不是可安装 APK：缺 AndroidManifest.xml")
        manifest = Manifest(zip_reader.read_bytes(manifest_entry, 32 * 1024 * 1024))
        outer_package = manifest.package()
        discovery = discover(zip_reader, args.max_probes)
        layout = discovery["layout"]
        injected_info = build_injected(zip_reader, layout, discovery["markers"])
        injected = injected_info["map"]
        sources = pick_sources(zip_reader, layout, injected)

        candidates: list[tuple[str, str]] = []
        if discovery["mode"] != "none":
            if sources["preferred"]:
                candidates = [(sources["preferred"], sources["preferred_reason"])]
                if args.scan_all:
                    candidates = collect_candidates(zip_reader, sources, args.max_candidates)
            else:
                candidates = collect_candidates(zip_reader, sources, args.max_candidates)

        import tempfile
        work_dir = tempfile.mkdtemp(prefix="embed-audit-")
        source_reports = []
        try:
            for name, reason in candidates:
                entry = zip_reader.by_name.get(name)
                if entry is None:
                    continue
                report = verify_source(zip_reader, entry, outer_package, injected, mode, work_dir)
                report["reason"] = reason
                source_reports.append(report)
                if report["v1"] and report["v2"] and report["v3"] and not args.scan_all:
                    break
        finally:
            try:
                os.rmdir(work_dir)
            except OSError:
                pass

        inferred_boot = []
        for report in source_reports:
            for name in report.get("v3_inferred_dex", []):
                if name not in inferred_boot:
                    inferred_boot.append(name)
        for name in inferred_boot:
            injected.setdefault(name, "boot_dex_inferred")
        if not injected_info["boot_dex"] and inferred_boot:
            injected_info["boot_dex"] = inferred_boot[0]

        host = None
        if args.source:
            source_path = os.path.abspath(args.source)
            if not os.path.isfile(source_path):
                raise AuditError("找不到 --source：%s" % source_path)
            source_handle = open(source_path, "rb")
            try:
                source_raw = Raw(source_handle, 0, os.path.getsize(source_path), owned=True)
                source_zip = ZipReader(source_raw, label="源包 ")
                host = compare_host(zip_reader, source_zip, injected, mode)
                host["path"] = source_path
            finally:
                source_handle.close()

        core = core_evidence(zip_reader, layout)
        tail = tail_info(zip_reader)
        total_size = sum(entry.size for entry in zip_reader.entries)
        verified_reports = [
            report for report in source_reports
            if report["v1"] and report["v2"] and report["v3"]
        ]
        verified = (
            max(verified_reports, key=lambda item: item["size"]) if verified_reports else None
        )
        primary = verified if verified is not None else (source_reports[0] if source_reports else None)
        twins = []
        other_verified = []
        if verified is not None:
            for report in verified_reports:
                if report is verified:
                    continue
                if report["size"] == verified["size"] and report["v3_hits"] == verified["v3_hits"]:
                    twins.append(report["name"])
                else:
                    other_verified.append(report["name"])
        second = twins

        xpatch_hits = manifest.xpatch_strings("xpatch")
        utf16_hits = manifest.raw_utf16_hits("xpatch")
        utf16_io_hits = manifest.raw_utf16_hits("io.xpatch")
        disallowed = [value for value in xpatch_hits if value != MODULE_CRASH_PERMISSION]

        def add(check_id, title, ok, detail="", skipped=False, gate=True):
            checks.append({
                "id": check_id,
                "title": title,
                "ok": bool(ok),
                "skipped": skipped,
                "gate": gate,
                "detail": detail,
            })

        layout_mode = discovery["mode"] == "layout"
        profile_old = args.expect == "old-layout"
        profile_new = args.expect == "new-layout"
        layout_expected = args.expect in ("obf", "embedded", "obf-static", "old-layout", "new-layout")
        layout_required = args.expect in ("obf", "obf-static", "old-layout", "new-layout")
        clean_expected = args.expect == "clean"
        add("zip", "APK 中央目录可解析", True, "条目 %d / 尾部 %d B" % (len(zip_reader.entries), tail["trailing_bytes"]))
        add("manifest", "清单可解析并取出包名", bool(outer_package), "package=%s" % (outer_package or "?"))
        if clean_expected:
            add(
                "layout",
                "未打包：无 XPatch 布局",
                discovery["mode"] == "none",
                "mode=%s config=%s" % (discovery["mode"], discovery["config_entry"]),
            )
        else:
            add(
                "layout",
                "布局可发现",
                discovery["mode"] != "none",
                "mode=%s config=%s" % (discovery["mode"], discovery["config_entry"]),
            )
        if layout_required:
            add(
                "layout_kind",
                "期望混淆布局（res_* 全表）",
                layout_mode,
                "mode=%s" % discovery["mode"],
            )
        if clean_expected:
            own = [name for name, category in injected.items()
                   if category.startswith("layout:") or category.startswith("legacy:") or
                   category.startswith("boot_dex")]
            add(
                "clean_injected",
                "未打包：无 XPatch 注入条目",
                len(own) == 0 and not injected_info["boot_candidates"],
                "XPatch 注入 %d 条；其它框架/旧族痕迹 %d 条 %s" % (
                    len(own),
                    len(injected) - len(own),
                    sorted(set(injected.values()) - {"layout:cfg"})[:6] if injected else [],
                ),
            )
        if layout_mode:
            missing_entries = [
                name for key, name in layout["entries"].items() if name not in zip_reader.by_name
            ]
            dir_prefix = layout["dir"] if layout["dir"].endswith("/") or not layout["dir"] else layout["dir"] + "/"
            dir_present = any(entry.name.startswith(dir_prefix) for entry in zip_reader.entries) if dir_prefix else False
            add(
                "layout_entries",
                "config 里的 res_* 条目都在条目表里",
                not missing_entries,
                "缺失 %d 条 %s" % (len(missing_entries), missing_entries[:5]),
            )
            add("layout_dir", "res_dir 目录下有条目", dir_present, "dir=%s" % layout["dir"])
            boot_ok = bool(injected_info["boot_dex"]) and looks_like_boot_dex(injected_info["boot_dex"])
            add(
                "layout_boot_dex",
                "res_boot_dex 是 classes*.dex 且存在",
                boot_ok,
                injected_info["boot_dex"] or "未识别",
            )
        elif layout is not None:
            missing_entries = [
                name for key, name in layout["entries"].items() if name not in zip_reader.by_name
            ]
            add(
                "layout_entries",
                "旧固定名条目都在条目表里",
                not missing_entries,
                "缺失 %d 条 %s" % (len(missing_entries), missing_entries[:5]),
            )
            add("layout_dir", "旧 xp/ 目录下有条目", any(
                entry.name.startswith("xp/") for entry in zip_reader.entries
            ), "dir=xp/")
            add(
                "layout_boot_dex",
                "引导桩 dex 可识别",
                bool(injected_info["boot_dex"]),
                (injected_info["boot_dex"] or "未识别") + "（旧布局按 res_core 标记或内嵌包差集推断）",
            )
        else:
            if clean_expected:
                add("layout_entries", "config 里的 res_* 条目都在条目表里", True, "未打包，跳过", True)
                add("layout_dir", "res_dir 目录下有条目", True, "未打包，跳过", True)
                add("layout_boot_dex", "引导桩 dex 可识别", True, "未打包，跳过", True)
            else:
                add("layout_entries", "config 里的 res_* 条目都在条目表里", True, "旧布局，无 res_*", True)
                add("layout_dir", "res_dir 目录下有条目", True, "旧布局，无 res_dir", True)
                add(
                    "layout_boot_dex",
                    "引导桩 dex 可识别",
                    bool(injected_info["boot_dex"]),
                    (injected_info["boot_dex"] or "未识别") + "（旧布局按 res_core 标记或内嵌包差集推断）",
                )

        add(
            "injected",
            "识别出注入条目",
            True if clean_expected else len(injected) > 0,
            "%d 条" % len(injected),
            skipped=clean_expected,
        )
        if clean_expected:
            add("dos_stamps", "注入条目时间戳众数符合档位", True, "未打包，跳过", True)

        if args.expect == "legacy":
            add(
                "legacy_mode",
                "旧静态产物：无 res_* 布局",
                discovery["mode"] == "legacy",
                "mode=%s" % discovery["mode"],
            )
            legacy_count = len(injected_info["xpatch_legacy"])
            legacy_ok = True
            detail = "%d 条旧族条目" % legacy_count
            if args.legacy_entries >= 0:
                legacy_ok = legacy_count == args.legacy_entries
                detail += "（期望 %d）" % args.legacy_entries
            add("legacy_entries", "旧族注入条目数符合预期", legacy_ok, detail)
            add(
                "legacy_source_absent",
                "旧静态产物不含内嵌 source",
                verified is None,
                "candidates=%d" % len(candidates),
            )
            hits_ok = utf16_hits == args.legacy_hits if args.legacy_hits >= 0 else utf16_hits > 0
            add(
                "legacy_manifest_hits",
                "清单含预期处数的 xpatch（UTF-16LE）",
                hits_ok,
                "实际 %d 处，期望 %d" % (utf16_hits, args.legacy_hits),
            )
        else:
            add("legacy_mode", "旧静态产物：无 res_* 布局", True, "mode=%s" % discovery["mode"],
                discovery["mode"] != "legacy")

        source_expected = args.expect in ("embedded", "obf", "old-layout", "new-layout") and not args.allow_no_source
        if source_expected:
            add("source_present", "存在可验证的内嵌 source", verified is not None, sources["preferred"] or "未找到")
        else:
            if verified is not None:
                add("source_present", "内嵌 source 情况", True, "内嵌 source 通过 V1–V3", gate=False)
            elif primary is not None:
                add(
                    "source_present",
                    "内嵌 source 情况",
                    True,
                    "内嵌 source 存在但未通过 V1–V3（%s）" % primary["detail"],
                    gate=False,
                )
            else:
                add("source_present", "内嵌 source 情况", True, "无内嵌 source（静态旧产物）", skipped=True)
        add(
            "source_unique",
            "内嵌候选唯一（同体积同命中数才算歧义，不同体积取最大）",
            len(twins) == 0,
            "歧义 twins：%s；其它通过候选（不同体积，取最大）：%s" % (
                twins[:5] if twins else "无",
                other_verified[:5] if other_verified else "无",
            ),
        )
        if primary is not None:
            add(
                "v1",
                "V1 内嵌包是 ZIP 且含清单与 classes*.dex",
                primary["v1"],
                "内嵌包 %d 条目；清单 %s；classes*.dex %d 个" % (
                    primary["entries"], "有" if primary["inner_manifest"] else "无", primary["inner_dex"]
                ),
            )
            add("v2", "V2 内嵌包名与外层一致", primary["v2"], "%s vs %s" % (primary["package"], outer_package))
            add("v3", "V3 外层未排除条目逐条同名同 size 同 CRC", primary["v3"], primary["detail"])
            if primary.get("v3_ignored_dex"):
                add(
                    "v3_ignored_dex",
                    "内嵌包中不存在的 classes*.dex 按注入跳过",
                    True,
                    "跳过：%s（与 Kotlin 新语义一致，不计 missing/mismatch）"
                    % ", ".join(primary["v3_ignored_dex"][:5]),
                    gate=False,
                )
            add(
                "v4",
                "V4 体积比（仅预筛信息，不再 gate）",
                True,
                "%s / %s = %.3f%s" % (
                    format_size(primary["size"]),
                    format_size(raw.size),
                    primary["v4_ratio"],
                    "" if primary["v4"] else "（< 0.5：旧判据会淘汰该候选，现只作预筛）",
                ),
                gate=False,
            )
        else:
            for check_id, title in (
                ("v1", "V1 内嵌包是 ZIP 且含清单与 classes*.dex"),
                ("v2", "V2 内嵌包名与外层一致"),
                ("v3", "V3 外层未排除条目逐条同名同 size 同 CRC"),
                ("v4", "V4 体积比（仅预筛信息，不再 gate）"),
            ):
                add(check_id, title, True, "无内嵌 source，跳过", True)

        if host is not None:
            add(
                "host_unchanged",
                "宿主条目相对 --source 未改动（name+crc+size）",
                host["changed"] == 0 and host["added"] == 0,
                "checked %d / changed %d / added %d / removed %d" % (
                    host["checked"], host["changed"], host["added"], host["removed"]
                ),
            )
        else:
            add("host_unchanged", "宿主条目相对 --source 未改动", True, "未给 --source，跳过", True)

        if layout_expected and layout_mode:
            add(
                "manifest_clean",
                "清单不含 io.xpatch/xpatch 类名（MODULE_CRASH 权限除外）",
                len(disallowed) == 0,
                "命中 %d 处 %s" % (len(xpatch_hits), xpatch_hits[:5]),
            )
            add(
                "legacy_absent",
                "混淆产物里不再残留 xp/* 旧族条目",
                len(injected_info["legacy_hits"]) == 0,
                "残留 %d 条 %s" % (
                    len(injected_info["legacy_hits"]), injected_info["legacy_hits"][:5]
                ),
            )
            sibling_bad = []
            host_names = [entry.name for entry in zip_reader.entries if entry.name not in injected]
            for name in injected:
                if "/" not in name:
                    continue
                directory = name.rsplit("/", 1)[0] + "/"
                if not any(other.startswith(directory) for other in host_names):
                    sibling_bad.append(name)
            add(
                "siblings",
                "注入条目都是宿主真实目录里的兄弟名",
                len(sibling_bad) == 0,
                ("[旧产物预期] " if profile_old and sibling_bad else "")
                + "异常 %d 条 %s" % (len(sibling_bad), sibling_bad[:5]),
                gate=not profile_old,
            )
        else:
            add("manifest_clean", "清单不含 io.xpatch/xpatch 类名（MODULE_CRASH 权限除外）", True,
                "命中 %d 处 %s（旧布局或期望 auto，不判定）" % (len(xpatch_hits), xpatch_hits[:5]), True)
            add("legacy_absent", "混淆产物里不再残留 xp/* 旧族条目", True, "非混淆产物，跳过", True)
            add("siblings", "注入条目都是宿主真实目录里的兄弟名", True, "非混淆产物，跳过", True)

        if injected and not clean_expected:
            stamps = {}
            for name in injected:
                entry = zip_reader.by_name.get(name)
                if entry is None:
                    continue
                stamps[entry.stamp()] = stamps.get(entry.stamp(), 0) + 1
            host_stamps = {}
            for entry in zip_reader.entries:
                if entry.name in injected:
                    continue
                host_stamps[entry.stamp()] = host_stamps.get(entry.stamp(), 0) + 1
            host_mode = max(host_stamps.items(), key=lambda item: item[1])[0] if host_stamps else "(无)"
            expected = host_mode if layout_mode else "1980-01-01 00:00:00"
            majority = max(stamps.items(), key=lambda item: item[1])[0]
            deviations = sorted(
                name for name in injected
                if name in zip_reader.by_name and zip_reader.by_name[name].stamp() != majority
            )
            outlier_detail = ""
            if deviations:
                outlier_detail = "；%d 条偏离 %s：%s" % (
                    len(deviations),
                    majority,
                    ", ".join(
                        "%s=%s" % (name, zip_reader.by_name[name].stamp())
                        for name in deviations[:5]
                    ),
                )
            add(
                "dos_stamps",
                "注入条目时间戳众数符合档位（布局=宿主众数，旧=1980 epoch）",
                majority == expected,
                ("[旧产物预期] " if profile_old and majority != expected else "")
                + "注入取值 %s；宿主众数 %s；期望 %s%s" % (
                    sorted(stamps.keys()), host_mode, expected, outlier_detail,
                ),
                gate=not profile_old,
            )
        else:
            add("dos_stamps", "注入条目时间戳一致", True, "未打包或无注入条目", True)

        if profile_new and not args.core_file:
            add(
                "core_file",
                "new-layout 必须给 --core-file 证明内核更新",
                False,
                "未给 --core-file",
            )
        if args.core_file:
            core_path = os.path.abspath(args.core_file)
            if not os.path.isfile(core_path):
                raise AuditError("找不到 --core-file：%s" % core_path)
            with open(core_path, "rb") as core_handle:
                expected_core = hashlib.sha256(core_handle.read()).hexdigest()
            core_ok = bool(core["sha256"]) and core["sha256"] == expected_core
            add(
                "core_matches",
                "res_core 与 --core-file 字节一致（内核已更新）",
                core_ok,
                ("[旧产物预期] " if profile_old and not core_ok else "")
                + "res_core %s / 参考 %s" % (core["sha256"][:16], expected_core[:16]),
                gate=not profile_old,
            )

        if args.source and verified is not None:
            source_file = os.path.abspath(args.source)
            if os.path.getsize(source_file) == verified["size"]:
                digest = 0
                with open(source_file, "rb") as handle:
                    while True:
                        block = handle.read(CHUNK)
                        if not block:
                            break
                        digest = zlib.crc32(block, digest)
                digest &= 0xFFFFFFFF
                add(
                    "source_matches_file",
                    "内嵌 source 与 --source 文件字节一致",
                    digest == verified["crc"],
                    "source crc %d / 声明 %d（体积相同 %s）" % (
                        digest, verified["crc"], format_size(verified["size"]),
                    ),
                )

        if mode == "deep":
            declared = verify_declared_crc(zip_reader, injected)
            add(
                "declared_crc",
                "中央目录声明的 CRC 与实际一致（deep）",
                len(declared["bad"]) == 0,
                "复核 %d 个 ≤8MB 条目，不一致 %d 个" % (declared["checked"], len(declared["bad"])),
            )
        else:
            add("declared_crc", "中央目录声明的 CRC 与实际一致（deep）", True, "未开 --deep，跳过", True)

        result = "PASS" if all(item["ok"] for item in checks if item["gate"]) else "FAIL"
        report = {
            "tool": "embed-audit",
            "schema": 1,
            "apk": apk_path,
            "size": raw.size,
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "mode": mode,
            "expect": args.expect,
            "entries": len(zip_reader.entries),
            "entries_total_size": total_size,
            "zip64": zip_reader.eocd["zip64"],
            "discovery": {
                "mode": discovery["mode"],
                "config_entry": discovery["config_entry"],
                "config_size": discovery["config_size"],
                "probes": discovery["probes"],
                "candidates": discovery["candidates"],
                "steps": discovery["steps"],
                "markers": discovery["markers"],
            },
            "layout": None if layout is None else {
                "family": layout.get("family", "layout"),
                "config_entry": layout["config_entry"],
                "dir": layout["dir"],
                "entries": layout["entries"],
                "boot_dex": layout["boot_dex"],
                "factory": layout["factory"],
                "provider": layout["provider"],
                "hex8": layout["hex8"],
                "runtime_class": layout["runtime_class"],
                "v": layout["v"],
                "enc": layout["enc"],
                "keys": layout["raw_keys"],
            },
            "boot_dex": injected_info["boot_dex"],
            "boot_candidates": injected_info["boot_candidates"],
            "injected": [
                {
                    "name": name,
                    "category": category,
                    "size": zip_reader.by_name[name].size,
                    "method": method_name(zip_reader.by_name[name].method),
                    "dos": zip_reader.by_name[name].stamp(),
                    "crc": zip_reader.by_name[name].crc,
                }
                for name, category in injected.items()
                if name in zip_reader.by_name
            ],
            "injected_count": len(injected),
            "legacy_hits": injected_info["legacy_hits"],
            "xpatch_legacy": injected_info["xpatch_legacy"],
            "sources": source_reports,
            "source_verified": None if verified is None else verified["name"],
            "source_second": second,
            "manifest": {
                "size": manifest_entry.size,
                "package": outer_package,
                "axml": manifest.axml,
                "xpatch_strings": xpatch_hits,
                "xpatch_utf16le_hits": utf16_hits,
                "io_xpatch_utf16le_hits": utf16_io_hits,
                "xpatch_utf8_hits": manifest.raw_utf8_hits("xpatch"),
            },
            "host_compare": host,
            "core": core,
            "tail": tail,
            "checks": checks,
            "result": result,
            "errors": errors,
            "duration_ms": int((time.time() - started) * 1000),
        }
        return report
    finally:
        raw.close()


def render(report: dict) -> None:
    print("== embed-audit " + os.path.basename(report["apk"]))
    print("   路径 " + report["apk"])
    print("   大小 %s（%d B）  条目 %d  解压总量 %s  模式 %s  期望 %s  耗时 %.2fs" % (
        format_size(report["size"]),
        report["size"],
        report["entries"],
        format_size(report["entries_total_size"]),
        report["mode"],
        report["expect"],
        report["duration_ms"] / 1000.0,
    ))
    print("")
    print("1. 布局发现（mode=%s）" % report["discovery"]["mode"])
    for step in report["discovery"]["steps"]:
        print("   [%s] %s: %s" % ("命中" if step["hit"] else "跳过", step["step"], step["detail"]))
    layout = report["layout"]
    if layout is not None:
        print("   config=%s（%s）" % (layout["config_entry"], format_size(report["discovery"]["config_size"])))
        if layout.get("family") == "legacy":
            print("   旧布局：固定名 xp/*，config 里没有 res_* 全表")
        else:
            print("   res_dir=%s  res_v=%s  res_enc=%s  res_hex8=%s" % (
                layout["dir"], layout["v"], layout["enc"], layout["hex8"] or "-",
            ))
            print("   res_factory=%s  res_runtime_class=%s" % (
                layout["factory"] or "-", layout["runtime_class"] or "-",
            ))
        print("   条目映射：")
        for key, name in layout["entries"].items():
            entry = None
            for item in report["injected"]:
                if item["name"] == name:
                    entry = item
                    break
            label = ("res_" if layout.get("family") != "legacy" else "旧 ") + key
            if entry is None:
                print("      %-14s %s  (缺失)" % (label, name))
            else:
                print("      %-14s %s  %s  %s  %s" % (
                    label, name, format_size(entry["size"]), entry["method"], entry["dos"],
                ))
    print("")
    print("2. 注入条目 %d 条（boot_dex=%s）" % (report["injected_count"], report["boot_dex"] or "未识别"))
    by_category = {}
    for item in report["injected"]:
        by_category.setdefault(item["category"], []).append(item)
    for category in sorted(by_category):
        names = by_category[category]
        print("   %-22s %d 条" % (category, len(names)))
    for item in report["injected"]:
        print("      %-58s %10s  %-8s  %s" % (
            item["name"], format_size(item["size"]), item["method"], item["dos"],
        ))
    print("")
    print("3. 内嵌 source")
    if not report["sources"]:
        print("   无内嵌 source（静态旧产物），V1–V3 跳过")
    for source in report["sources"]:
        print("   候选 %s（%s，%s，来源 %s）" % (
            source["name"], format_size(source["size"]), source["method"], source.get("reason", "-"),
        ))
        print("      V1 %s  内嵌包 %d 条目，清单 %s，classes*.dex %d" % (
            "通过" if source["v1"] else "失败", source["entries"],
            "有" if source["inner_manifest"] else "无", source["inner_dex"],
        ))
        print("      V2 %s  内嵌包名 %s / 外层 %s" % (
            "通过" if source["v2"] else "失败", source["package"], report["manifest"]["package"],
        ))
        print("      V3 %s  checked %d / missing %d / mismatch %d（排除 %d 条，命中 %d 条）" % (
            "通过" if source["v3"] else "失败",
            source["v3_checked"], source.get("v3_missing_count", 0),
            source.get("v3_mismatch_count", 0), source.get("v3_excluded", 0),
            source.get("v3_excluded_present", 0),
        ))
        if source.get("v3_ignored_dex"):
            print("         classes*.dex 按注入跳过 " + ", ".join(source["v3_ignored_dex"][:5]))
        if source.get("v3_missing_sample"):
            print("         missing 样例 " + ", ".join(source["v3_missing_sample"][:5]))
        if source.get("v3_mismatch_sample"):
            print("         mismatch 样例 " + json.dumps(source["v3_mismatch_sample"][:3], ensure_ascii=False))
        print("      V4（仅预筛信息，不 gate）比例 %.3f  %s / %s" % (
            source["v4_ratio"], format_size(source["size"]), format_size(report["size"]),
        ))
        if source.get("inner_only"):
            print("         内嵌包独有条目 %d 条（V3 只要求外层 ⊆ 内嵌包）" % source["inner_only"])
    host = report["host_compare"]
    print("")
    print("4. 宿主对比")
    if host is None:
        print("   未给 --source，跳过（只做声明字段级 V3）")
    else:
        print("   源包 %s（%s，%d 条目）" % (host["path"], format_size(host["source"]), host["source_entries"]))
        print("   同名 %d 条：改动 %d，新增 %d，移除 %d（排除集合 %d 条）" % (
            host["checked"], host["changed"], host["added"], host["removed"], host["excluded"],
        ))
        if host["changed_sample"]:
            print("   改动样例 " + json.dumps(host["changed_sample"][:3], ensure_ascii=False))
        if host["added_sample"]:
            print("   新增样例 " + ", ".join(host["added_sample"][:5]))
        if host["removed_sample"]:
            print("   移除样例 " + ", ".join(host["removed_sample"][:5]))
    print("")
    print("5. 清单痕迹（UTF-16LE）")
    manifest = report["manifest"]
    print("   包名 %s；AndroidManifest.xml %s（%s）" % (
        manifest["package"], format_size(manifest["size"]), "AXML" if manifest["axml"] else "非 AXML",
    ))
    print("   xpatch UTF-16LE 命中 %d 处（io.xpatch %d 处），UTF-8 %d 处" % (
        manifest["xpatch_utf16le_hits"], manifest["io_xpatch_utf16le_hits"], manifest["xpatch_utf8_hits"],
    ))
    for value in manifest["xpatch_strings"][:8]:
        print("      字符串 %s" % value)
    print("")
    print("6. 尾部与中央目录")
    tail = report["tail"]
    print("   EOCD 之后多余字节 %d；中央目录前空隙 %d（含签名块）；数据位于中央目录之后的条目 %d" % (
        tail["trailing_bytes"], tail["gap_before_cd"], len(tail["data_after_cd"]),
    ))
    if tail["tail_append"]:
        print("   尾部追加命中 " + ", ".join(tail["data_after_cd"][:5]))
    core = report["core"]
    print("")
    print("7. 内核")
    if core["ok"]:
        print("   %s  %s  sha256=%s" % (core["name"], format_size(core["size"]), core["sha256"]))
    else:
        print("   未找到 res_core / xp/core.dex")
    print("")
    print("8. 断言")
    for check in report["checks"]:
        mark = "PASS" if check["ok"] else "FAIL"
        if check["skipped"]:
            mark = "SKIP"
        elif not check["gate"]:
            mark = "INFO"
        print("   [%s] %-46s %s" % (mark, check["title"], check["detail"]))
    failed = [check for check in report["checks"] if not check["ok"] and check["gate"]]
    print("")
    if failed:
        print("RESULT: FAIL（%d 项）" % len(failed))
        for check in failed:
            print("   - " + check["id"] + ": " + check["detail"])
    else:
        print("RESULT: PASS")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="embed-audit.py",
        description="离线审计 XPatch 产物的注入布局与内嵌来源（V1–V3 判定，V4 仅预筛信息），退出码 0=通过 1=断言失败 2=输入/解析错误",
    )
    parser.add_argument("apk", help="待审计 APK")
    parser.add_argument("--json", action="store_true", help="只输出机器可读 JSON")
    parser.add_argument("--source", default="", help="可选原包，用于宿主条目逐字节（name+crc+size）对比")
    parser.add_argument(
        "--expect",
        default="auto",
        choices=("auto", "legacy", "embedded", "obf", "obf-static", "old-layout", "new-layout", "clean"),
        help=(
            "期望形态：legacy=旧静态产物 embedded=带内嵌来源（任意布局，V1–V3 判定） "
            "obf=res_* 布局 + 内嵌来源 obf-static=布局混淆但无内嵌 "
            "old-layout=旧版固定 assets/<hex8>/ 布局（三项指纹只做信息性输出） "
            "new-layout=T5 新产物（兄弟名/时间戳/内核/清单四项全判） clean=未打包"
        ),
    )
    parser.add_argument("--fast", action="store_true", help="只比 size，不比 CRC（大文件省时间）")
    parser.add_argument("--deep", action="store_true", help="额外复核中央目录声明的 CRC 与实际一致")
    parser.add_argument("--max-probes", type=int, default=MAX_CONFIG_PROBES, help="布局反向探测上限")
    parser.add_argument("--max-candidates", type=int, default=MAX_CANDIDATES, help="内嵌候选尝试上限")
    parser.add_argument("--scan-all", action="store_true", help="验证全部内嵌候选（查歧义）")
    parser.add_argument("--core-file", default="", help="可选参考内核 dex，与 res_core 对比 sha256")
    parser.add_argument("--allow-no-source", action="store_true", help="带内嵌期望的档位里放宽 source 必需（静态产物）")
    parser.add_argument("--legacy-hits", type=int, default=2, help="--expect legacy 时清单里 xpatch UTF-16LE 的期望处数，-1 表示只要求 ≥1")
    parser.add_argument("--legacy-entries", type=int, default=-1, help="--expect legacy 时旧族注入条目的期望数，-1 表示不校验")
    return parser


def main(argv: list[str]) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.fast and args.deep:
        print("ERROR: --fast 与 --deep 不能同时使用", file=sys.stderr)
        return 2
    try:
        report = audit(args)
    except AuditError as error:
        print("ERROR: %s" % error, file=sys.stderr)
        return 2
    except (OSError, struct.error, zlib.error) as error:
        print("ERROR: %s: %s" % (type(error).__name__, error), file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        render(report)
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
