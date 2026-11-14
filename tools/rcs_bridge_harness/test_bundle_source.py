from pathlib import Path
import subprocess
import tempfile
import unittest

from bundle_source import include_harness, upstream_source_paths


class SourceAllowlistTest(unittest.TestCase):
    def test_added_patch_source_is_included_without_untracked_auth_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'upstream'
            patches = root / 'patches'
            source.mkdir()
            patches.mkdir()
            subprocess.run(['git', 'init', '-q', str(source)], check=True)
            (source / 'LICENSE').write_text('Example public source license')
            subprocess.run(['git', '-C', str(source), 'add', 'LICENSE'], check=True)
            (source / 'cookies.json').write_text('test-only private placeholder')
            (patches / 'test.patch').write_text(
                'diff --git a/pkg/libgm/harness_test.go b/pkg/libgm/harness_test.go\n'
                'new file mode 100644\n'
                '--- /dev/null\n'
                '+++ b/pkg/libgm/harness_test.go\n'
                '@@ -0,0 +1 @@\n'
                '+package libgm\n')
            self.assertEqual(upstream_source_paths(source, patches),
                             ['LICENSE', 'pkg/libgm/harness_test.go'])

    def test_runtime_credentials_and_outputs_are_excluded(self):
        for path in (
            'cookies.json', 'session.json', 'auth/login.json', 'auth/cookies.txt',
            'android/app/debug.keystore', 'android/app/libs/rcsbridge.aar',
            'build/session.json', 'build/upstream/auth.json', '.git/config',
            'bridge/session.cookies', 'bridge/auth.session', '../private.go',
            '/tmp/private.go',
        ):
            with self.subTest(path=path):
                self.assertFalse(include_harness(path))

    def test_rebuild_source_and_licenses_are_included(self):
        for path in (
            'LICENSE', 'README.md', 'build.sh', 'bridge/go.mod', 'bridge/go.sum',
            'bridge/bridge.go', 'bridge/bridge_test.go',
            'bridge/patches/reject-multiple-phones.patch',
            'auth/prepare_login.py', 'auth/test_prepare_login.py',
            'android/app/src/main/AndroidManifest.xml',
            'android/app/src/main/kotlin/app/openbubbles/rcsprototype/MainActivity.kt',
        ):
            with self.subTest(path=path):
                self.assertTrue(include_harness(path))


if __name__ == '__main__':
    unittest.main()
