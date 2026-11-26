#!/usr/bin/env python3
"""
GNFL NOTE V1.1 - Dynamic DEX Bytecode Patcher & Checksum Updater
Patches:
1. PlainWhiteboardActivity.onActivityResult -> PdfPicker.onActivityResult
2. PlainWhiteboardActivity.dispatchTouchEvent -> SmartPenManager.dispatchTouchEvent
3. PopupX BadTokenException bypass
"""

import sys
import zlib
import hashlib
import struct

def read_uleb128(data, off):
    val = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        val |= (b & 0x7f) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return val, off

def patch_dex(dex_path, output_path):
    with open(dex_path, "rb") as f:
        data = bytearray(f.read())

    print(f"[+] Loaded DEX: {dex_path} ({len(data)} bytes)")

    # 1. Parse DEX header
    string_ids_size, string_ids_off, type_ids_size, type_ids_off, proto_ids_size, proto_ids_off, field_ids_size, field_ids_off, method_ids_size, method_ids_off, class_defs_size, class_defs_off = struct.unpack_from('<IIIIIIIIIIII', data, 56)

    strings = []
    for i in range(string_ids_size):
        off = string_ids_off + i * 4
        str_data_off = struct.unpack_from('<I', data, off)[0]
        pos = str_data_off
        while data[pos] & 0x80: pos += 1
        pos += 1
        end = data.find(b'\x00', pos)
        strings.append(data[pos:end].decode('utf-8', errors='replace'))

    types = [strings[struct.unpack_from('<I', data, type_ids_off + i*4)[0]] for i in range(type_ids_size)]
    methods = []
    for i in range(method_ids_size):
        class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', data, method_ids_off + i*8)
        methods.append((types[class_idx], strings[name_idx]))

    # 2. Find hook target method IDs (V2.0: route through UpdateBootstrap for GitHub OTA DEX hot-update!)
    pdf_mid = None
    pen_mid = None
    for i, m in enumerate(methods):
        if m[0] == 'Lkr/hs/gnflhs/whiteboard/UpdateBootstrap;' and m[1] == 'onActivityResult':
            pdf_mid = i
        elif m[0] == 'Lkr/hs/gnflhs/whiteboard/UpdateBootstrap;' and m[1] == 'dispatchTouchEvent':
            pen_mid = i

    if pdf_mid is None:
        raise RuntimeError("UpdateBootstrap.onActivityResult method not found in DEX!")
    if pen_mid is None:
        raise RuntimeError("UpdateBootstrap.dispatchTouchEvent method not found in DEX!")

    print(f"[+] Hook Method IDs: UpdateBootstrap.onActivityResult=0x{pdf_mid:04x}, UpdateBootstrap.dispatchTouchEvent=0x{pen_mid:04x}")

    # 3. Locate PlainWhiteboardActivity methods
    pwa_on_activity_result_off = None
    pwa_dispatch_touch_off = None

    for i in range(class_defs_size):
        class_idx = struct.unpack_from('<I', data, class_defs_off + i*32)[0]
        if types[class_idx] == 'Lcom/seewo/easinote/PlainWhiteboardActivity;':
            class_data_off = struct.unpack_from('<I', data, class_defs_off + i*32 + 24)[0]
            pos = class_data_off
            sfs, pos = read_uleb128(data, pos); ifs, pos = read_uleb128(data, pos)
            dms, pos = read_uleb128(data, pos); vms, pos = read_uleb128(data, pos)
            for _ in range(sfs): _, pos = read_uleb128(data, pos); _, pos = read_uleb128(data, pos)
            for _ in range(ifs): _, pos = read_uleb128(data, pos); _, pos = read_uleb128(data, pos)
            m_idx = 0
            for _ in range(dms):
                diff, pos = read_uleb128(data, pos); m_idx += diff
                _, pos = read_uleb128(data, pos); code_off, pos = read_uleb128(data, pos)
            m_idx = 0
            for _ in range(vms):
                diff, pos = read_uleb128(data, pos); m_idx += diff
                _, pos = read_uleb128(data, pos); code_off, pos = read_uleb128(data, pos)
                m_name = methods[m_idx][1]
                if m_name == 'onActivityResult':
                    pwa_on_activity_result_off = code_off + 16
                elif m_name == 'dispatchTouchEvent':
                    pwa_dispatch_touch_off = code_off + 16

    if pwa_on_activity_result_off is None:
        raise RuntimeError("PlainWhiteboardActivity.onActivityResult not found!")
    if pwa_dispatch_touch_off is None:
        raise RuntimeError("PlainWhiteboardActivity.dispatchTouchEvent not found!")

    # 4. Patch PlainWhiteboardActivity.onActivityResult
    old_res = bytes(data[pwa_on_activity_result_off:pwa_on_activity_result_off+6])
    new_res = bytes([0x71, 0x40, pdf_mid & 0xff, (pdf_mid >> 8) & 0xff, 0x10, 0x32])
    data[pwa_on_activity_result_off:pwa_on_activity_result_off+6] = new_res
    print(f"[+] Patched onActivityResult at 0x{pwa_on_activity_result_off:x}: {old_res.hex()} -> {new_res.hex()}")

    # 5. Patch PlainWhiteboardActivity.dispatchTouchEvent
    old_touch = bytes(data[pwa_dispatch_touch_off:pwa_dispatch_touch_off+6])
    new_touch = bytes([0x71, 0x20, pen_mid & 0xff, (pen_mid >> 8) & 0xff, 0x10, 0x00])
    data[pwa_dispatch_touch_off:pwa_dispatch_touch_off+6] = new_touch
    print(f"[+] Patched dispatchTouchEvent at 0x{pwa_dispatch_touch_off:x}: {old_touch.hex()} -> {new_touch.hex()}")

    # 6. Patch PopupX BadToken bypass
    pat = bytes.fromhex('1302170034200600')
    idx = data.find(pat)
    if idx != -1:
        data[idx+8:idx+14] = bytes([0x00]*6)
        print(f"[+] Patched PopupX at 0x{idx+8:x} -> 6 NOPs")

    # 6b. Patch Max Zoom Scale 300% (3.0f = 0x4040) -> 500% (5.0f = 0x40a0)
    # Target 1: com.seewo.easinote.p4.c.m.h() (Page getScale clamp 0.3f ~ 3.0f)
    pat_m_h = bytes.fromhex('150040402d0201003d0204001501404014009a99993e')
    idx_m_h = data.find(pat_m_h)
    if idx_m_h != -1:
        data[idx_m_h+2:idx_m_h+4] = bytes.fromhex('a040')
        data[idx_m_h+14:idx_m_h+16] = bytes.fromhex('a040')
        print(f"[+] Patched Max Zoom 300% -> 500% in p4.c.m.h() at 0x{idx_m_h:x}")
    else:
        print("[!] Warning: p4.c.m.h() zoom clamp pattern not found")

    # Target 2: com.seewo.easinote.p4.d.d0.c2.j() (Pinch/Roam zoom clamp 0.3f ~ 3.0f)
    pat_c2_j = bytes.fromhex('150840402d0901083a090400')
    idx_c2_j = data.find(pat_c2_j)
    if idx_c2_j != -1:
        data[idx_c2_j+2:idx_c2_j+4] = bytes.fromhex('a040')
        print(f"[+] Patched Max Zoom 300% -> 500% in p4.d.d0.c2.j() at 0x{idx_c2_j:x}")
    else:
        print("[!] Warning: p4.d.d0.c2.j() zoom clamp pattern not found")

    # 7. Recalculate SHA-1 Checksum
    sha1 = hashlib.sha1(data[32:]).digest()
    data[12:32] = sha1
    print(f"[+] Updated SHA-1: {sha1.hex()}")

    # 8. Recalculate Adler32 Checksum
    adler = zlib.adler32(data[12:]) & 0xffffffff
    data[8:12] = adler.to_bytes(4, 'little')
    print(f"[+] Updated Adler32: 0x{adler:08x}")

    with open(output_path, "wb") as f:
        f.write(data)
    print(f"[+] Successfully wrote patched DEX to {output_path}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python patch_dex.py <input_dex> <output_dex>")
        sys.exit(1)
    patch_dex(sys.argv[1], sys.argv[2])
