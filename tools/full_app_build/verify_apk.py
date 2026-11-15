#!/usr/bin/env python3
"""Inspect an experimental APK without installing or executing it."""

import argparse
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile


RCS_ALPHA_PACKAGE = 'com.bluebubbles.messaging.rcsalpha'
RCS_SERVICE = 'com.bluebubbles.messaging.services.rcs.RcsBridgeService'
RCS_CLASSES = (RCS_SERVICE, 'com.bluebubbles.messaging.services.rcs.NativeRcsPlugin')


def dex_defined_classes(data):
    """Read class definitions, rather than accepting unused DEX string references."""
    if len(data) < 112 or not data.startswith(b'dex\n'):
        raise ValueError('Invalid DEX header')
    if struct.unpack_from('<I', data, 40)[0] != 0x12345678:
        raise ValueError('Unsupported DEX byte order')
    string_count, strings, type_count, types = struct.unpack_from('<IIII', data, 56)
    class_count, classes = struct.unpack_from('<II', data, 96)
    for count, offset, width in ((string_count, strings, 4), (type_count, types, 4),
                                 (class_count, classes, 32)):
        if offset + count * width > len(data):
            raise ValueError('Invalid DEX table bounds')
    result = set()
    for index in range(class_count):
        type_index = struct.unpack_from('<I', data, classes + index * 32)[0]
        if type_index >= type_count:
            raise ValueError('Invalid DEX class type')
        string_index = struct.unpack_from('<I', data, types + type_index * 4)[0]
        if string_index >= string_count:
            raise ValueError('Invalid DEX class descriptor')
        cursor = struct.unpack_from('<I', data, strings + string_index * 4)[0]
        for _ in range(5):  # Skip the UTF-16 length encoded as ULEB128.
            if cursor >= len(data):
                raise ValueError('Truncated DEX class descriptor')
            byte = data[cursor]
            cursor += 1
            if not byte & 0x80:
                break
        else:
            raise ValueError('Invalid DEX string length')
        end = data.find(b'\0', cursor)
        if end < 0:
            raise ValueError('Unterminated DEX class descriptor')
        result.add(data[cursor:end])
    return result


def manifest_services(xmltree):
    services = set()
    element = None
    for line in xmltree.splitlines():
        tag = re.match(r'\s*E: (\S+)', line)
        if tag:
            element = tag.group(1)
        elif element == 'service':
            name = re.search(r'A: (?:http://schemas.android.com/apk/res/)?android:name[^=]*="([^"]+)"', line)
            if name:
                services.add(name.group(1))
    return services


def contains_rcs_auth(value):
    if isinstance(value, dict):
        sensitive = {'SAPISID', 'HSID', 'SSID', 'APISID', '__Secure-1PSIDTS',
                     'request_crypto', 'refresh_key', 'tachyon_token', 'web_encryption_key'}
        if any(key in value and value[key] for key in sensitive):
            return True
        return any(contains_rcs_auth(child) for child in value.values())
    if isinstance(value, list):
        return any(contains_rcs_auth(child) for child in value)
    return False


def inspect_rcs_archive(archive, xmltree):
    errors = []
    names = archive.namelist()
    if 'lib/arm64-v8a/libgojni.so' not in names:
        errors.append('RCS alpha is missing the arm64 Google Messages native bridge')
    if not {RCS_SERVICE, '.services.rcs.RcsBridgeService'} & manifest_services(xmltree):
        errors.append('RCS alpha is missing its declared background service')
    definitions = set()
    for name in names:
        if re.fullmatch(r'classes(?:\d+)?\.dex', name):
            definitions.update(dex_defined_classes(archive.read(name)))
    for name in RCS_CLASSES:
        descriptor = ('L' + name.replace('.', '/') + ';').encode('ascii')
        if descriptor not in definitions:
            errors.append('RCS alpha is missing compiled class ' + name)
    suspicious = 0
    for entry in archive.infolist():
        name = entry.filename
        lower = name.lower()
        basename = lower.rsplit('/', 1)[-1]
        sensitive_name = (lower.endswith(('.curl', '.har', '.keystore', '.jks'))
                          or basename in {'cookies.txt', 'login.json', 'session.json', 'session.enc',
                                          'credentials.json', 'credentials.txt'}
                          or re.search(r'(rcs|bridge|google.?messages).*(login|session|credential).*\.(json|enc|txt)$', basename))
        if sensitive_name:
            suspicious += 1
            continue
        if not name.startswith(('assets/', 'res/raw/')) or entry.is_dir():
            continue
        data = archive.read(entry)
        # Inspect data assets only. Code contains cookie field names legitimately.
        if re.search(rb'(?:^|[;\s\x22\x27])SAPISID=[^;\s\x22\x27]+', data):
            suspicious += 1
            continue
        if data.lstrip().startswith((b'{', b'[')):
            try:
                value = json.loads(data)
                contains_auth = contains_rcs_auth(value)
            except RecursionError:
                suspicious += 1  # Fail closed when a data asset cannot be inspected.
                continue
            except (UnicodeDecodeError, ValueError):
                continue
            if contains_auth:
                suspicious += 1
    if suspicious:
        # Do not print the entry names or their potentially sensitive contents.
        errors.append(f'RCS alpha contains {suspicious} possible login/session assets; remove them before distribution')
    return errors


def elf_load_alignment(data):
    if data[:4] != b'\x7fELF' or len(data) < 64:
        raise ValueError('not a complete ELF file')
    if data[5] not in (1, 2):
        raise ValueError('unsupported ELF byte order')
    endian = '<' if data[5] == 1 else '>'
    machine = struct.unpack_from(endian + 'H', data, 18)[0]
    if data[4] == 2:
        offset = struct.unpack_from(endian + 'Q', data, 32)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 54)
        minimum_size = 56
    elif data[4] == 1:
        offset = struct.unpack_from(endian + 'I', data, 28)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 42)
        minimum_size = 32
    else:
        raise ValueError('unsupported ELF class')
    if size < minimum_size or offset + size * count > len(data):
        raise ValueError('invalid ELF program headers')
    loads = []
    for index in range(count):
        header = offset + index * size
        kind = struct.unpack_from(endian + 'I', data, header)[0]
        if kind != 1:  # PT_LOAD
            continue
        if data[4] == 2:
            file_offset, virtual_address = struct.unpack_from(endian + 'QQ', data, header + 8)
            alignment = struct.unpack_from(endian + 'Q', data, header + 48)[0]
        else:
            file_offset, virtual_address = struct.unpack_from(endian + 'II', data, header + 4)
            alignment = struct.unpack_from(endian + 'I', data, header + 28)[0]
        loads.append((alignment, (virtual_address - file_offset) % 16384 == 0))
    if not loads:
        raise ValueError('ELF has no loadable segments')
    return machine, min(alignment for alignment, _ in loads), all(
        alignment >= 16384 and compatible for alignment, compatible in loads
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--expected-package', default='com.bluebubbles.messaging.alpha')
    parser.add_argument('--sdk-root', default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    apk = args.apk.resolve()
    output = args.output or apk.parent / 'APK-CHECKS.txt'
    report = [f'APK: {apk.name}', f'Expected package: {args.expected_package}']
    errors = []
    warnings = []
    try:
        if not apk.is_file():
            raise ValueError(f'APK does not exist: {apk}')
        if not args.sdk_root:
            raise ValueError('Set ANDROID_SDK_ROOT or pass --sdk-root')
        build_tools = Path(args.sdk_root) / 'build-tools' / '36.0.0'

        def command(name, *arguments):
            tool = build_tools / name
            if not tool.is_file():
                raise ValueError(f'Missing Android build tool: {tool}')
            return subprocess.run([str(tool), *map(str, arguments)], text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)

        badging = command('aapt2', 'dump', 'badging', apk)
        if badging.returncode:
            raise ValueError('aapt2 could not inspect the APK: ' + badging.stdout.strip())
        selected = [line for line in badging.stdout.splitlines()
                    if line.startswith(('package:', 'sdkVersion:', 'minSdkVersion:', 'targetSdkVersion:', 'native-code:'))]
        report.extend(selected)
        package = re.search(r"^package: name='([^']+)'", badging.stdout, re.MULTILINE)
        if package is None or package.group(1) != args.expected_package:
            actual = package.group(1) if package else 'unreadable'
            raise ValueError(f'Package mismatch: expected {args.expected_package}, found {actual}')
        manifest = None
        if args.expected_package == RCS_ALPHA_PACKAGE:
            manifest = command('aapt2', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml')
            if manifest.returncode:
                raise ValueError('aapt2 could not inspect RCS alpha component declarations')

        signature = command('apksigner', 'verify', '--verbose', '--print-certs', apk)
        report.extend(['', 'Android signature verification:', signature.stdout.strip()])
        if signature.returncode:
            errors.append('Android signature verification failed')

        ordinary_alignment = command('zipalign', '-c', '4', apk)
        report.append('APK 4-byte ZIP alignment: ' + ('PASS' if ordinary_alignment.returncode == 0 else 'FAIL'))
        if ordinary_alignment.returncode:
            errors.append('APK standard ZIP alignment failed')
        page_alignment = command('zipalign', '-c', '-P', '16', '4', apk)
        report.append('APK 16 KiB ZIP alignment: ' + ('PASS' if page_alignment.returncode == 0 else 'WARNING'))
        if page_alignment.returncode:
            warnings.append('Uncompressed native libraries do not all have 16 KiB ZIP alignment')

        report.extend(['', 'Native ELF load-segment alignment:'])
        with zipfile.ZipFile(apk) as archive:
            corrupt_entry = archive.testzip()
            if corrupt_entry is not None:
                raise ValueError(f'ZIP integrity check failed: {corrupt_entry}')
            if manifest is not None:
                rcs_errors = inspect_rcs_archive(archive, manifest.stdout)
                errors.extend(rcs_errors)
                report.append('RCS native bridge, compiled plugin and background service: '
                              + ('PASS' if not rcs_errors else 'FAIL'))
                report.append('Recognized RCS login/session assets: '
                              + ('NONE' if not any('login/session' in error for error in rcs_errors) else 'FAIL'))
            libraries = sorted(name for name in archive.namelist()
                               if name.startswith('lib/') and name.endswith('.so'))
            if not libraries:
                raise ValueError('APK contains no native libraries')
            abis = sorted({name.split('/')[1] for name in libraries})
            report.append('Packaged ABIs: ' + ', '.join(abis))
            if abis != ['arm64-v8a']:
                errors.append('Expected only the arm64-v8a ABI')
            for name in libraries:
                machine, alignment, compatible = elf_load_alignment(archive.read(name))
                if machine != 183:  # EM_AARCH64
                    errors.append(f'Unexpected ELF architecture in {name}: {machine}')
                report.append(f'{name}: minimum PT_LOAD alignment={alignment} bytes; '
                              + ('16 KiB layout PASS' if compatible else '16 KiB layout WARNING'))
                if not compatible:
                    warnings.append(f'{name} needs validation/rebuilding for 16 KiB pages')
        report.append('ZIP integrity: PASS')
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as exc:
        errors.append(str(exc))

    report.extend(['', 'Errors: ' + str(len(errors)), *('ERROR: ' + text for text in errors),
                   'Warnings: ' + str(len(warnings)), *('WARNING: ' + text for text in warnings),
                   '', 'This verifies packaging only. App startup, account setup and messaging require device testing.'])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(report) + '\n')
    print(f'APK checks: {len(errors)} errors, {len(warnings)} warnings; report: {output}')
    for error in errors:
        print('ERROR: ' + error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
