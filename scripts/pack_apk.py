import sys
import os
import zipfile

def pack_apk(src_dir, out_apk):
    print(f"[+] Packaging APK from {src_dir} to {out_apk}...")
    if os.path.exists(out_apk):
        os.remove(out_apk)
    with zipfile.ZipFile(out_apk, 'w') as zf:
        for root, dirs, files in os.walk(src_dir):
            for f in files:
                full_path = os.path.join(root, f)
                rel_path = os.path.relpath(full_path, src_dir).replace('\\', '/')
                # In Android APKs, assets, native libraries (.so), resources.arsc, and classes.dex
                # must be STORED (uncompressed) so AssetManager.openFd() and ART mmap work without error.
                if (rel_path.startswith('assets/') or 
                    rel_path.endswith('.so') or 
                    rel_path == 'resources.arsc' or 
                    rel_path == 'classes.dex'):
                    compress_type = zipfile.ZIP_STORED
                else:
                    compress_type = zipfile.ZIP_DEFLATED
                zf.write(full_path, rel_path, compress_type=compress_type)
    print(f"[+] Packaging complete: {os.path.getsize(out_apk)} bytes")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python pack_apk.py <src_dir> <out_apk>")
        sys.exit(1)
    pack_apk(sys.argv[1], sys.argv[2])
