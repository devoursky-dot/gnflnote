#!/usr/bin/env python3
"""
GNFL NOTE - Binary AndroidManifest.xml (AXML) Version Patcher
Idempotently updates android:versionCode (int) and android:versionName (string)
in a compiled binary AndroidManifest.xml without needing apktool.
"""
import struct
import sys

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_ELEMENT_TYPE = 0x0102

ATTR_VERSION_CODE = 0x0101021B
ATTR_VERSION_NAME = 0x0101021C
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10


def read_utf16_string(buf, offset):
    length, = struct.unpack_from('<H', buf, offset)
    pos = offset + 2
    if length & 0x8000:
        low, = struct.unpack_from('<H', buf, pos)
        length = ((length & 0x7FFF) << 16) | low
        pos += 2
    raw = buf[pos:pos + length * 2]
    return raw.decode('utf-16le')


def encode_utf16_string(s):
    raw = s.encode('utf-16le')
    n = len(raw) // 2
    if n > 0x7FFF:
        hdr = struct.pack('<HH', 0x8000 | (n >> 16), n & 0xFFFF)
    else:
        hdr = struct.pack('<H', n)
    return hdr + raw + b'\x00\x00'


def patch_manifest(path, new_version_code, new_version_name):
    data = bytearray(open(path, 'rb').read())
    hdr_type, hdr_size, file_size = struct.unpack_from('<HHI', data, 0)
    if hdr_type != RES_XML_TYPE:
        raise ValueError(f'Not a binary AXML file (type=0x{hdr_type:x})')

    # 1. Locate StringPool, ResourceMap, and first StartElement (<manifest>)
    pos = hdr_size
    sp_offset = None
    sp_size = 0
    res_map = []
    version_code_data_offset = None
    version_name_str_idx = None
    old_version_code = None

    while pos < len(data):
        c_type, c_hdr, c_size = struct.unpack_from('<HHI', data, pos)
        if c_size < 8:
            break
        if c_type == RES_STRING_POOL_TYPE:
            sp_offset = pos
            sp_size = c_size
        elif c_type == RES_XML_RESOURCE_MAP_TYPE:
            count = (c_size - c_hdr) // 4
            res_map = list(struct.unpack_from(f'<{count}I', data, pos + c_hdr))
        elif c_type == RES_XML_START_ELEMENT_TYPE:
            # ResXMLTree_attrExt starts at pos + c_hdr
            # ns(4), name(4), attrStart(2), attrSize(2), attrCount(2), idIdx(2), classIdx(2), styleIdx(2)
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', data, pos + c_hdr + 8)
            attr_base = pos + c_hdr + attr_start
            for i in range(attr_count):
                a_off = attr_base + i * attr_size
                # ns(4), name(4), rawValue(4), typedValue: size(2), res0(1), dataType(1), data(4)
                a_ns, a_name, a_raw, tv_size, tv_res0, tv_type, tv_data = struct.unpack_from('<IIIHBBI', data, a_off)
                res_id = res_map[a_name] if a_name < len(res_map) else 0
                if res_id == ATTR_VERSION_CODE:
                    old_version_code = tv_data
                    version_code_data_offset = a_off + 16
                elif res_id == ATTR_VERSION_NAME:
                    version_name_str_idx = tv_data if tv_type == TYPE_STRING else a_raw
            break  # Only inspect root <manifest>
        pos += c_size

    if sp_offset is None or version_code_data_offset is None or version_name_str_idx is None:
        raise RuntimeError('Could not locate versionCode/versionName in AndroidManifest.xml')

    # 2. Patch versionCode in-place (4-byte little-endian int)
    struct.pack_into('<I', data, version_code_data_offset, int(new_version_code))

    # 3. Rebuild UTF-16LE StringPool with updated versionName
    _, sp_hdr, _, str_cnt, style_cnt, flags, str_start, style_start = struct.unpack_from('<HHIIIIII', data, sp_offset)
    if flags & 0x100:
        raise RuntimeError('UTF-8 StringPool not supported by this patcher')
    if style_cnt != 0:
        raise RuntimeError('Styled StringPool not supported by this patcher')

    offsets_base = sp_offset + sp_hdr
    str_offsets = list(struct.unpack_from(f'<{str_cnt}I', data, offsets_base))
    strings_base = sp_offset + str_start

    strings = [read_utf16_string(data, strings_base + off) for off in str_offsets]
    old_version_name = strings[version_name_str_idx]
    strings[version_name_str_idx] = str(new_version_name)

    new_offsets = []
    str_blob = bytearray()
    for s in strings:
        new_offsets.append(len(str_blob))
        str_blob.extend(encode_utf16_string(s))

    # Pad string pool chunk to 4-byte boundary
    while (str_start + len(str_blob)) % 4 != 0:
        str_blob.append(0)

    new_sp_size = str_start + len(str_blob)
    new_sp_chunk = bytearray()
    new_sp_chunk.extend(struct.pack('<HHIIIIII',
                                    RES_STRING_POOL_TYPE, sp_hdr, new_sp_size,
                                    str_cnt, 0, flags, str_start, 0))
    for off in new_offsets:
        new_sp_chunk.extend(struct.pack('<I', off))
    # Any gap between offset table and str_start (usually 0)
    if len(new_sp_chunk) < str_start:
        new_sp_chunk.extend(b'\x00' * (str_start - len(new_sp_chunk)))
    new_sp_chunk.extend(str_blob)

    # 4. Splice new StringPool into manifest and update total AXML file size
    after_sp = data[sp_offset + sp_size:]
    out = bytearray(data[:sp_offset])
    out.extend(new_sp_chunk)
    out.extend(after_sp)
    struct.pack_into('<I', out, 4, len(out))

    with open(path, 'wb') as f:
        f.write(out)

    print(f'[+] Patched AndroidManifest.xml: versionCode {old_version_code} -> {new_version_code}, '
          f'versionName "{old_version_name}" -> "{new_version_name}"')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        print('Usage: patch_version.py <AndroidManifest.xml> <versionCode> <versionName>')
        sys.exit(1)
    patch_manifest(sys.argv[1], int(sys.argv[2]), sys.argv[3])
