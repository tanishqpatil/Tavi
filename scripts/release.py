#!/usr/bin/env python3
"""Validate a signed Tavi APK and prepare public release files; never reads signing passwords."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
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


def verify(apk, tools, output):
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
    if "ShareFixture" in manifest or "sharefixture" in manifest:
        raise ValueError("Refusing to publish test-only share components")
    if not re.search(r"^sdkVersion:'26'$", badging, re.M):
        raise ValueError("Unexpected minimum Android SDK")
    with zipfile.ZipFile(apk) as archive:
        abis = {path.split('/')[1] for path in archive.namelist() if path.startswith('lib/') and path.endswith('.so')}
        if abis != {"arm64-v8a"} or "lib/arm64-v8a/libtavi.so" not in archive.namelist():
            raise ValueError("Expected only the ARM64 native runtime")
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"Tavi-{name}-arm64.apk"
    shutil.copyfile(apk, target)
    hasher = hashlib.sha256()
    with target.open('rb') as stream:
        for block in iter(lambda: stream.read(64 * 1024), b''):
            hasher.update(block)
    digest = hasher.hexdigest()
    (output / "SHA256SUMS.txt").write_text(f"{digest}  {target.name}\n")
    commit = command("git", "rev-parse", "HEAD")
    native = command("git", "-C", "native/llama.cpp", "rev-parse", "HEAD")
    (output / "BUILD-INFO.txt").write_text(
        f"Tavi {name}\nVersion code: {code}\nCommit: {commit}\nNative runtime: {native}\n"
        f"Application ID: app.lfm3.probe\nABI: arm64-v8a\nMinimum Android: 8 (API 26)\n"
        f"Release certificate SHA-256: {expected}\nDebuggable: false\n"
        "APK signature and 16 KB ZIP alignment verified. No model weights included.\n"
        "All four packs have synthetic real-model emulator checks; camera/microphone hardware, speech quality and phone performance remain experimental.\n")
    (output / "RELEASE-NOTES.txt").write_text(
        f"Tavi {name} — {'development prerelease' if is_prerelease(name) else 'release'}\n\n"
        "Download the ARM64 APK below; no GitHub login or Actions artifact ZIP is needed. "
        "Android 8+ required. Download models inside Tavi.\n\n"
        "Local LFM3 text chat, private history, model manager and optional keyless web search/MCP. "
        "Reviewed Android text/photo sharing, persistent draft attachments, previewed phone diagnostics "
        "and stop-on-audio-interruption are included. "
        "This phase adds bounded CPU tuning and native prefill timing, with optimized kernels kept experimental. "
        "All four packs have synthetic emulator checks, including combined photo/voice/speech and cancellation. "
        "Developer view includes generated-speech duration/rate and first decoded audio timing. "
        "Online tools are off by default. Photo/voice paths are experimental and require phone testing. "
        "Speech generation was slower than real time on the emulator; no phone speedup is claimed. "
        "No physical-device RAM/performance tier is qualified.\n\n"
        "This APK uses the stable Tavi release signing key. It cannot update an existing debug APK "
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
    args = parser.parse_args()
    try:
        if args.action == "metadata":
            metadata(args.tag)
        else:
            verify(args.apk.resolve(), args.build_tools.resolve(), args.output.resolve())
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"Release validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
