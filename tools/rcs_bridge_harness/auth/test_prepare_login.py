# SPDX-License-Identifier: AGPL-3.0-or-later
"""Offline tests use invented cookie values and never invoke the clipboard or network."""

import contextlib
import io
import json
import os
from pathlib import Path
import shlex
import stat
import tempfile
import unittest
from unittest import mock

import prepare_login as login


FAKE_COOKIES = {name: "fake-" + name + "=value" for name in login.REQUIRED_COOKIES}


def cookie_string(cookies=None):
    return "; ".join(name + "=" + value for name, value in (cookies or FAKE_COOKIES).items())


def curl_request(cookies=None, url=login.EXPECTED_URL):
    return "curl " + shlex.quote(url) + " -H " + shlex.quote("Cookie: " + cookie_string(cookies))


class ParsingTests(unittest.TestCase):
    def test_header_extracts_only_allowed_names(self):
        source = dict(FAKE_COOKIES, unwanted="must-not-export", **{"__Secure-1PSIDTS": "fake-optional"})
        result = login.extract_cookies(curl_request(source))
        self.assertEqual(result, dict(FAKE_COOKIES, **{"__Secure-1PSIDTS": "fake-optional"}))

    def test_cookie_option_and_equals(self):
        for option in ("-b ", "--cookie ", "--cookie=", "-b"):
            with self.subTest(option=option):
                command = "curl --compressed -X GET " + shlex.quote(login.EXPECTED_URL)
                command += " " + option + shlex.quote(cookie_string())
                self.assertEqual(login.extract_cookies(command), FAKE_COOKIES)

    def test_realistic_multiline_firefox_copy(self):
        command = "curl '" + login.EXPECTED_URL + "' \\\n  -H 'User-Agent: Mozilla/5.0 (Macintosh)' \\\n  -H \"Accept: */*\" \\\n  -H " + shlex.quote("Cookie: " + cookie_string()) + " \\\n  --compressed\n"
        self.assertEqual(login.extract_cookies(command), FAKE_COOKIES)

    def test_header_forms_and_escaped_quote_in_unused_header(self):
        for option in ("--header=", "-H"):
            command = "curl --url " + shlex.quote(login.EXPECTED_URL)
            command += " -H " + shlex.quote("X-Example: user's browser")
            command += " " + option + shlex.quote("cOoKiE: " + cookie_string())
            self.assertEqual(login.extract_cookies(command), FAKE_COOKIES)

    def test_foreign_or_modified_urls_rejected(self):
        urls = [
            "http://messages.google.com/web/config", "https://example.com/web/config",
            "https://messages.google.com.evil.test/web/config", "https://messages.google.com@evil.test/web/config",
            "https://user@messages.google.com/web/config", "https://messages.google.com:443/web/config",
            "https://messages.google.com/web/config/", "https://messages.google.com/web/config?authuser=0",
            "https://messages.google.com/web/config#fragment", "https://messages.google.com/web/other",
        ]
        for url in urls:
            with self.subTest(url=url), self.assertRaises(login.LoginInputError):
                login.extract_cookies(curl_request(url=url))

    def test_multiple_urls_and_shell_commands_rejected(self):
        for suffix in (" " + login.EXPECTED_URL, " https://example.com", " ; touch example", " && echo bad", " | sh"):
            with self.subTest(suffix=suffix), self.assertRaises(login.LoginInputError):
                login.extract_cookies(curl_request() + suffix)

    def test_command_substitution_never_executes(self):
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / "must-not-exist"
            for payload in ("$(touch " + str(marker) + ")", "`touch " + str(marker) + "`", "${HOME}"):
                source = dict(FAKE_COOKIES, SID=payload)
                with mock.patch.object(login.subprocess, "run") as run:
                    with self.assertRaises(login.LoginInputError):
                        login.extract_cookies(curl_request(source))
                    run.assert_not_called()
                self.assertFalse(marker.exists())

    def test_cookie_file_inputs_rejected(self):
        for value in ("/tmp/cookies.txt", "@cookies.txt", "@SID=fake"):
            with self.subTest(value=value), self.assertRaises(login.LoginInputError):
                login.extract_cookies("curl " + login.EXPECTED_URL + " --cookie " + shlex.quote(value))
        with self.assertRaises(login.LoginInputError):
            login.extract_cookies("curl " + login.EXPECTED_URL + " -H @headers.txt")

    def test_duplicate_cookies_and_multiple_cookie_headers_rejected(self):
        for added in ("; SID=other", "; unknown=one; unknown=two"):
            with self.subTest(added=added), self.assertRaises(login.LoginInputError):
                login.extract_cookies("curl " + login.EXPECTED_URL + " -b " + shlex.quote(cookie_string() + added))
        with self.assertRaises(login.LoginInputError):
            login.extract_cookies(curl_request() + " -b " + shlex.quote(cookie_string()))

    def test_missing_empty_and_malformed_cookies_rejected(self):
        missing = dict(FAKE_COOKIES)
        missing.pop("OSID")
        for source in (missing, dict(FAKE_COOKIES, SID=""), dict(FAKE_COOKIES, SID="two words"),
                       dict(FAKE_COOKIES, SID='bad"quote'), dict(FAKE_COOKIES, SID="bad,comma"),
                       dict(FAKE_COOKIES, SID="bad\\slash")):
            with self.subTest(source=list(source)), self.assertRaises(login.LoginInputError):
                login.extract_cookies(curl_request(source))

    def test_controls_and_unescaped_newlines_rejected(self):
        for character in ("\r\n", "\r", "\x00", "\t", "\x7f", "\x85", "\n"):
            with self.subTest(character=repr(character)), self.assertRaises(login.LoginInputError):
                login.extract_cookies(curl_request(dict(FAKE_COOKIES, SID="fake" + character + "bad")))
        with self.assertRaises(login.LoginInputError):
            login.extract_cookies(curl_request() + "\necho bad")

    def test_malformed_command_and_unnecessary_options_rejected(self):
        commands = ["", "echo harmless", "curl 'unfinished", "curl --url", "curl " + login.EXPECTED_URL,
                    curl_request() + " --insecure", curl_request() + " --data-raw test",
                    curl_request() + " -X POST"]
        for command in commands:
            with self.subTest(command=command[:12]), self.assertRaises(login.LoginInputError):
                login.extract_cookies(command)

    def test_oversize_input_and_cookie_rejected(self):
        with self.assertRaises(login.LoginInputError):
            login.extract_cookies("x" * (login.MAX_INPUT_BYTES + 1))
        with self.assertRaises(login.LoginInputError):
            login.extract_cookies(curl_request(dict(FAKE_COOKIES, SID="x" * (login.MAX_COOKIE_VALUE + 1))))


class FileAndCliTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        # macOS may return a /var symlink in its temporary-directory path.
        self.root = Path(self.temporary.name).resolve()
        self.output = self.root / "RCS Bridge Login.json"

    def test_private_file_and_exact_json(self):
        login.write_private_json(self.output, FAKE_COOKIES)
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o600)
        self.assertEqual(json.loads(self.output.read_text()), FAKE_COOKIES)

    def test_existing_output_never_overwritten(self):
        self.output.write_text("keep existing data")
        with self.assertRaises(OSError):
            login.write_private_json(self.output, FAKE_COOKIES)
        self.assertEqual(self.output.read_text(), "keep existing data")

    def test_output_symlink_rejected(self):
        target = self.root / "target"
        target.write_text("unchanged")
        self.output.symlink_to(target)
        with self.assertRaises(OSError):
            login.write_private_json(self.output, FAKE_COOKIES)
        self.assertEqual(target.read_text(), "unchanged")

    def test_symlink_parent_rejected(self):
        target = self.root / "real"
        target.mkdir()
        link = self.root / "linked"
        link.symlink_to(target, target_is_directory=True)
        with self.assertRaises(OSError):
            login.write_private_json(link / "login.json", FAKE_COOKIES)
        self.assertFalse((target / "login.json").exists())

    def test_cli_local_input_never_launches_program_and_prints_no_secret(self):
        source = self.root / "request.txt"
        source.write_text(curl_request())
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.object(login.subprocess, "run") as run:
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = login.main(["--input", str(source), "--output", str(self.output)])
            run.assert_not_called()
        self.assertEqual(result, 0)
        for value in FAKE_COOKIES.values():
            self.assertNotIn(value, stdout.getvalue() + stderr.getvalue())
        self.assertEqual(json.loads(self.output.read_text()), FAKE_COOKIES)

    def test_cli_rejects_existing_output_without_leaking_input(self):
        source = self.root / "request.txt"
        source.write_text(curl_request())
        self.output.write_text("preserve")
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result = login.main(["--input", str(source), "--output", str(self.output)])
        self.assertEqual(result, 1)
        self.assertEqual(self.output.read_text(), "preserve")
        self.assertNotIn("fake-", stderr.getvalue())

    def test_cli_clipboard_requires_explicit_mac_option(self):
        with mock.patch.object(login.sys, "platform", "linux"), mock.patch.object(login.subprocess, "run") as run:
            with contextlib.redirect_stderr(io.StringIO()):
                result = login.main(["--clipboard", "--output", str(self.output)])
            run.assert_not_called()
        self.assertEqual(result, 1)

    def test_clipboard_call_is_fixed_and_mocked(self):
        fake_result = mock.Mock(stdout=curl_request().encode("utf-8"))
        with mock.patch.object(login.sys, "platform", "darwin"):
            with mock.patch.object(login.subprocess, "run", return_value=fake_result) as run:
                with contextlib.redirect_stdout(io.StringIO()):
                    result = login.main(["--clipboard", "--output", str(self.output)])
        self.assertEqual(result, 0)
        self.assertEqual(run.call_args.args[0], ["/usr/bin/pbpaste"])
        self.assertNotIn("shell", run.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
