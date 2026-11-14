#!/usr/bin/env python3
"""Inspect an experimental APK without installing or executing it."""

import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile


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
