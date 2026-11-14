#!/usr/bin/env python3
"""Package public corresponding source using an explicit allowlist, never app data."""

import argparse
from pathlib import Path, PurePosixPath
import subprocess
import zipfile

HARNESS = PurePosixPath('tools/rcs_bridge_harness')
EXACT = {
    'LICENSE', 'README.md', '.gitignore', '.gitattributes', 'build.sh', 'bundle_source.py',
    'test_bundle_source.py', 'android/settings.gradle', 'android/build.gradle',
    'android/gradle.properties', 'android/app/build.gradle',
    'auth/prepare_login.py', 'auth/test_prepare_login.py', 'auth/README.md',
    'bridge/THIRD-PARTY.md',
}


def include_harness(relative):
    path = PurePosixPath(relative)
    if path.is_absolute() or '..' in path.parts:
        return False
    text = path.as_posix()
    if text in EXACT:
        return True
    if text in ('bridge/go.mod', 'bridge/go.sum'):
        return True
    if text.startswith('bridge/') and path.suffix in ('.go', '.patch', '.md'):
        return True
    if text.startswith('android/app/src/') and path.suffix in ('.kt', '.java', '.xml'):
        return True
    return False


def add_file(archive, source, name):
    if source.is_symlink() or not source.is_file():
        raise ValueError(f'Expected a regular source file: {name}')
    archive.write(source, str(name))


def upstream_source_paths(upstream, patch_directory):
    paths = set(filter(None, subprocess.check_output(
        ['git', '-C', str(upstream), 'ls-files', '-z'], text=True
    ).split('\0')))
    # git apply does not add new test files to the index. Include exactly the
    # paths named by our source patches, never arbitrary untracked runtime files.
    for patch in sorted(patch_directory.glob('*.patch')):
        records = subprocess.check_output(
            ['git', '-C', str(upstream), 'apply', '--numstat', '-z', str(patch)], text=True
        ).split('\0')
        for record in filter(None, records):
            fields = record.split('\t', 2)
            if len(fields) != 3 or not fields[2]:
                raise ValueError('Unsupported source patch path record')
            paths.add(fields[2])
    return sorted(paths)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root, upstream = args.root.resolve(), args.upstream.resolve()
    harness = root / str(HARNESS)
    public_paths = upstream_source_paths(upstream, harness / 'bridge/patches')
    commit = subprocess.check_output(
        ['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True
    ).strip()
    source_revision = subprocess.run(
        ['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
    )
    source_commit = source_revision.stdout.strip() if source_revision.returncode == 0 else 'source-archive'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for source in sorted(harness.rglob('*')):
            relative = source.relative_to(harness).as_posix()
            if include_harness(relative):
                add_file(archive, source, HARNESS / relative)
        for relative in sorted(filter(None, public_paths)):
            path = PurePosixPath(relative)
            if path.is_absolute() or '..' in path.parts or '.git' in path.parts:
                raise ValueError('Unexpected upstream path')
            add_file(archive, upstream / relative, PurePosixPath('upstream/gmessages') / path)
        for relative in (
            '.github/workflows/rcs-bridge-prototype.yml',
            'tools/full_app_build/verify_apk.py',
        ):
            add_file(archive, root / relative, relative)
        archive.writestr('SOURCE.txt',
            'Corresponding source for the isolated RCS Bridge Test.\n'
            f'OpenBubbles fork source commit: {source_commit}\n'
            f'Upstream Google Messages client commit: {commit}\n'
            'upstream/gmessages contains the public pinned source with the safety patches applied.\n'
            'The harness build script fetches that same public pin and applies the included patches.\n'
            'Go dependencies and tool versions are recorded in go.mod/go.sum and build.sh.\n'
            'The Android SDK/NDK, JDK, Gradle and Go compiler are external build prerequisites.\n'
            'This archive excludes account data, cookies, sessions, signing keys and compiled outputs.\n')
    print(f'Corresponding source archive: {args.output.name}')


if __name__ == '__main__':
    main()
