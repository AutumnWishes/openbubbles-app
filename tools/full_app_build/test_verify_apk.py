import io
import json
import struct
import unittest
import zipfile

from verify_apk import RCS_CLASSES, RCS_SERVICE, dex_defined_classes, inspect_rcs_archive


def class_dex(names, definitions=True):
    """Small DEX table fixture: class references and definitions are independent."""
    descriptors = [('L' + name.replace('.', '/') + ';').encode() for name in names]
    count = len(descriptors)
    strings = 112
    types = strings + 4 * count
    classes = types + 4 * count
    data = bytearray(classes + (32 * count if definitions else 0))
    data[:8] = b'dex\n035\0'
    struct.pack_into('<I', data, 40, 0x12345678)
    struct.pack_into('<IIII', data, 56, count, strings, count, types)
    struct.pack_into('<II', data, 96, count if definitions else 0, classes)
    for index, descriptor in enumerate(descriptors):
        struct.pack_into('<I', data, strings + 4 * index, len(data))
        struct.pack_into('<I', data, types + 4 * index, index)
        if definitions:
            struct.pack_into('<I', data, classes + 32 * index, index)
        data.extend(bytes([len(descriptor)]) + descriptor + b'\0')
    return bytes(data)


MANIFEST = f'''E: manifest
  E: application
    E: service
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="{RCS_SERVICE}" (Raw: "{RCS_SERVICE}")
'''


class RcsPackagingTest(unittest.TestCase):
    def inspect(self, extras=None, *, native=True, classes=RCS_CLASSES, manifest=MANIFEST,
                definitions=True):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            archive.writestr('classes.dex', class_dex(classes, definitions))
            if native:
                archive.writestr('lib/arm64-v8a/libgojni.so', b'native fixture')
            for name, content in (extras or {}).items():
                archive.writestr(name, content)
        buffer.seek(0)
        with zipfile.ZipFile(buffer) as archive:
            return inspect_rcs_archive(archive, manifest)

    def test_complete_clean_package(self):
        self.assertEqual(self.inspect({'assets/config.json': '{"theme":"blue"}'}), [])

    def test_short_android_namespace_is_supported(self):
        self.assertEqual(self.inspect(manifest=MANIFEST.replace('http://schemas.android.com/apk/res/', '')), [])

    def test_requires_native_arm64_bridge(self):
        self.assertTrue(any('native bridge' in error for error in self.inspect(native=False)))

    def test_requires_real_class_definitions(self):
        errors = self.inspect(definitions=False)
        self.assertEqual(sum('compiled class' in error for error in errors), 2)

    def test_requires_plugin_definition(self):
        errors = self.inspect(classes=(RCS_SERVICE,))
        self.assertTrue(any('NativeRcsPlugin' in error for error in errors))

    def test_activity_name_does_not_count_as_service(self):
        errors = self.inspect(manifest=MANIFEST.replace('E: service', 'E: activity'))
        self.assertTrue(any('background service' in error for error in errors))

    def test_rejects_cookie_json_with_arbitrary_asset_name(self):
        errors = self.inspect({'assets/theme.data': json.dumps({'nested': {'SAPISID': 'invented-only'}})})
        self.assertTrue(any('login/session' in error for error in errors))
        self.assertFalse(any('invented-only' in error or 'theme.data' in error for error in errors))

    def test_rejects_paired_auth_json(self):
        errors = self.inspect({'assets/config.json': json.dumps({'version': 1, 'auth': {'web_encryption_key': 'invented-only'}})})
        self.assertTrue(any('login/session' in error for error in errors))

    def test_rejects_encrypted_session_file(self):
        errors = self.inspect({'assets/rcs-paired-session.enc': b'opaque fixture'})
        self.assertTrue(any('login/session' in error for error in errors))

    def test_rejects_copied_request_without_printing_it(self):
        errors = self.inspect({'assets/request.data': b"curl 'https://messages.google.com/web/config' -H 'Cookie: SAPISID=invented-only;'"})
        self.assertTrue(any('login/session' in error for error in errors))
        self.assertFalse(any('invented-only' in error for error in errors))

    def test_rejects_invalid_dex_bounds(self):
        data = bytearray(class_dex(RCS_CLASSES))
        struct.pack_into('<I', data, 100, len(data) + 1)
        with self.assertRaisesRegex(ValueError, 'bounds'):
            dex_defined_classes(data)


if __name__ == '__main__':
    unittest.main()
