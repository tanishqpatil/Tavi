#!/usr/bin/env python3
"""Validate a signed Tavi APK and prepare public release files; never reads signing passwords."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def command(*args):
    return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def version():
    values = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    name = values["taviVersionName"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", name):
        raise ValueError("Invalid release version name")
    code = int(values["taviVersionCode"])
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("Invalid Android version code")
    return name, code


def is_prerelease(name):
    values = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    flag = values.get("taviPrerelease", "false")
    if flag not in {"true", "false"}:
        raise ValueError("taviPrerelease must be true or false")
    return "-" in name or flag == "true"


def metadata(tag):
    name, code = version()
    if tag != "v" + name:
        raise ValueError("Release tag must match taviVersionName in gradle.properties")
    commit = command("git", "rev-parse", "HEAD")
    if command("git", "rev-parse", f"refs/tags/{tag}^{{commit}}") != commit:
        raise ValueError("The checked-out commit does not match the existing release tag")
    command("git", "merge-base", "--is-ancestor", "HEAD", "origin/main")
    # Values are validated before being written to GitHub step outputs.
    print(f"tag={tag}\nversion={name}\ncode={code}\ncommit={commit}\nprerelease={'true' if is_prerelease(name) else 'false'}")


def verify(apk, tools, output, variant="portable"):
    name, code = version()
    expected = (ROOT / "assets/tavi-release-certificate.sha256").read_text().strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Missing or malformed public signing certificate fingerprint")
    signature = command(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    certificates = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if [cert.lower() for cert in certificates] != [expected]:
        raise ValueError("APK signing certificate does not match the project's release identity")
    command(str(tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(apk))
    badging = command(str(tools / "aapt"), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != ("app.lfm3.probe", str(code), name):
        raise ValueError("APK application ID/version does not match the release configuration")
    if "application-debuggable" in badging:
        raise ValueError("Refusing to publish a debuggable APK")
    manifest = command(str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml")
    profile = re.search(r'app\.lfm3\.probe\.RUNTIME_PROFILE[^\n]*\n[^\n]*android:value[^\n]*?="([a-z-]+)"', manifest)
    if not profile or profile.group(1) != variant:
        raise ValueError("APK runtime profile does not match its download label")
    if "ShareFixture" in manifest or "sharefixture" in manifest:
        raise ValueError("Refusing to publish test-only share components")
    if not re.search(r"^sdkVersion:'26'$", badging, re.M):
        raise ValueError("Unexpected minimum Android SDK")
    with zipfile.ZipFile(apk) as archive:
        if any(path.startswith('lib/') and path.endswith('/libOpenCL.so') for path in archive.namelist()):
            raise ValueError("Link-only OpenCL SDK loader must not be packaged as a phone driver")
        abis = {path.split('/')[1] for path in archive.namelist() if path.startswith('lib/') and path.endswith('.so')}
        if abis != {"arm64-v8a"} or "lib/arm64-v8a/libtavi.so" not in archive.namelist():
            raise ValueError("Expected only the ARM64 native runtime")
        libs = {path.rsplit('/', 1)[-1] for path in archive.namelist() if path.startswith('lib/') and path.endswith('.so')}
        if ("libLiteRt.so" in libs) != (variant == "litert-lab") or ("libggml-opencl.so" in libs) != (variant == "opencl"):
            raise ValueError("APK native libraries do not match the selected runtime variant")
        for path in archive.namelist():
            if not path.startswith('lib/') or not path.endswith('.so'):
                continue
            data = archive.read(path)
            if data[:6] != b'\x7fELF\x02\x01':
                raise ValueError("Expected little-endian ARM64 ELF libraries")
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            if size < 56 or offset + size * count > len(data):
                raise ValueError("Invalid ELF program headers")
            for index in range(count):
                header = struct.unpack_from('<IIQQQQQQ', data, offset + index * size)
                if header[0] == 1 and (header[7] < 16384 or header[2] % 16384 != header[3] % 16384):
                    raise ValueError("Native ELF load segments are not 16 KB compatible")
    output.mkdir(parents=True, exist_ok=True)
    suffix = "" if variant == "portable" else f"-{variant}"
    target = output / f"Tavi-{name}-arm64{suffix}.apk"
    shutil.copyfile(apk, target)
    hasher = hashlib.sha256()
    with target.open('rb') as stream:
        for block in iter(lambda: stream.read(64 * 1024), b''):
            hasher.update(block)
    digest = hasher.hexdigest()
    sums = output / "SHA256SUMS.txt"
    entries = dict(line.split("  ", 1)[::-1] for line in sums.read_text().splitlines()) if sums.exists() else {}
    entries[target.name] = digest
    sums.write_text(''.join(f"{value}  {key}\n" for key, value in sorted(entries.items())))
    commit = command("git", "rev-parse", "HEAD")
    native = command("git", "-C", "native/llama.cpp", "rev-parse", "HEAD")
    info = "BUILD-INFO.txt" if variant == "portable" else f"BUILD-INFO-{variant}.txt"
    (output / info).write_text(
        f"Tavi {name}\nRuntime profile: {variant}\nVersion code: {code}\nCommit: {commit}\nNative runtime: {native}\n"
        f"Application ID: app.lfm3.probe\nABI: arm64-v8a\nMinimum Android: 8 (API 26)\n"
        f"Release certificate SHA-256: {expected}\nDebuggable: false\n"
        "APK signature, 16 KB ZIP and native ELF alignment verified. No model weights included.\n"
        "Four GGUF packs have synthetic emulator checks. OpenCL has no physical GPU result. KleidiAI was slower in the initial emulator comparison.\n"
        "LiteRT lab graph creation failed SIGILL on this emulator; its incompatible CPU profile is now rejected. LiteRT inference is unqualified.\n"
        "Camera/microphone hardware, speech quality and phone performance remain experimental.\n")
    (output / "RELEASE-NOTES.txt").write_text(
        f"Tavi {name} — {'development prerelease' if is_prerelease(name) else 'release'}\n\n"
        "Start with Tavi-" + name + "-arm64.apk (portable CPU). No GitHub login or Actions artifact ZIP is needed. "
        "Android 8+ required. Download models inside Tavi.\n\n"
        "Local LFM3 text chat, private history, model manager and optional keyless web search/MCP. "
        "Reviewed Android text/photo sharing, persistent draft attachments, previewed phone diagnostics "
        "and stop-on-audio-interruption are included. "
        "This phase adds bounded CPU tuning, native prefill timing and separate runtime experiments. "
        "The cpu-optimized APK includes KleidiAI (slower in the initial emulator comparison); the opencl APK enables explicit vendor-driver testing with CPU media. "
        "The litert-lab APK adds an unqualified community INT8 Omni conversion. Its SDK graph creation failed SIGILL on the emulator; SME2-without-SME CPU profiles are now rejected. "
        "LiteRT inference has not passed and no LiteRT speedup is claimed. It requires about 11 GB initial free storage for downloads/derived caches and a fixed 4096-token context allocation. "
        "All four packs have synthetic emulator checks, including combined photo/voice/speech and cancellation. "
        "Developer view includes generated-speech duration/rate and first decoded audio timing. "
        "Online tools are off by default. Photo/voice paths are experimental and require phone testing. "
        "Speech generation was slower than real time on the emulator; no phone speedup is claimed. "
        "No physical-device RAM/performance tier is qualified.\n\n"
        "All four APK variants use the same stable key, application ID and version code; install one at a time. Switching variants preserves private data; remove a lab-only pack in the lab APK to reclaim its storage. "
        "These APKs cannot update an existing debug APK "
        "signed with a different key. Export conversations first if you choose to uninstall that build; "
        "uninstalling removes its private chats and downloaded models. Future release updates must use "
        "the same release key and a higher version code.\n\n"
        f"Source commit: {commit}\nRelease certificate SHA-256: {expected}\n"
        "SHA256SUMS.txt verifies the APK download; BUILD-INFO.txt records the build identity.\n")
    print(f"Verified signed, non-debuggable ARM64 release: {target.name}")
    print(f"APK SHA-256: {digest}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    meta = sub.add_parser("metadata")
    meta.add_argument("--tag", required=True)
    check = sub.add_parser("verify")
    check.add_argument("--apk", type=Path, required=True)
    check.add_argument("--build-tools", type=Path, required=True)
    check.add_argument("--output", type=Path, default=ROOT / "dist")
    check.add_argument("--variant", choices=["portable", "cpu-optimized", "opencl", "litert-lab"], default="portable")
    args = parser.parse_args()
    try:
        if args.action == "metadata":
            metadata(args.tag)
        else:
            verify(args.apk.resolve(), args.build_tools.resolve(), args.output.resolve(), args.variant)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"Release validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
