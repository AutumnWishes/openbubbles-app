#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Prepare a local Google Messages login file without executing the copied request."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


EXPECTED_URL = "https://messages.google.com/web/config"
REQUIRED_COOKIES = ("SID", "HSID", "SSID", "APISID", "SAPISID", "OSID")
ALLOWED_COOKIES = REQUIRED_COOKIES + ("__Secure-1PSIDTS",)
MAX_INPUT_BYTES = 131072
MAX_COOKIE_VALUE = 8192
TOKEN = re.compile(r"^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$")
NO_ARGUMENT_FLAGS = {
    "--compressed", "--globoff", "--http1.1", "--http2", "--http3",
    "--silent", "--show-error", "--fail", "-s", "-S", "-sS", "-f",
}


class LoginInputError(ValueError):
    """A deliberately non-sensitive error safe to display to the user."""


def _normalize_command(text):
    if len(text.encode("utf-8")) > MAX_INPUT_BYTES:
        raise LoginInputError("The copied request is too large.")
    if any((ord(c) < 32 and c != "\n") or 127 <= ord(c) < 160 for c in text):
        raise LoginInputError("The copied request contains unsupported control characters.")
    # A cURL copy is data, never shell code. Reject shell expansion syntax too.
    if any(marker in text for marker in ("$(", "${", "`")):
        raise LoginInputError("Shell expansion syntax is not accepted.")
    text = text.strip(" \n")
    normalized = []
    quote = None
    i = 0
    while i < len(text):
        char = text[i]
        if char == "\\" and quote != "'" and i + 1 < len(text):
            following = text[i + 1]
            if following == "\n":
                if quote is not None:
                    raise LoginInputError("Line breaks inside quoted values are not accepted.")
                i += 2
                continue
            normalized.extend((char, following))
            i += 2
            continue
        if char == "\n":
            raise LoginInputError("Use one copied request, with normal cURL line continuations.")
        if char in ("'", '"'):
            if quote is None:
                quote = char
            elif quote == char:
                quote = None
        normalized.append(char)
        i += 1
    try:
        return shlex.split("".join(normalized), posix=True, comments=False)
    except ValueError:
        raise LoginInputError("The copied request has invalid quoting.") from None


def _parse_cookie_string(value):
    if value.startswith("@") or "=" not in value:
        raise LoginInputError("Cookie files are not accepted; copy the request's Cookie header.")
    parsed = {}
    pieces = value.split(";")
    for index, part in enumerate(pieces):
        part = part.lstrip(" ")
        if not part and index == len(pieces) - 1:
            continue
        name, separator, cookie_value = part.partition("=")
        if not separator or not TOKEN.fullmatch(name) or not cookie_value:
            raise LoginInputError("The copied request contains a malformed cookie.")
        if name in parsed:
            raise LoginInputError("Duplicate cookie names are not accepted; use a fresh private window.")
        if len(cookie_value) > MAX_COOKIE_VALUE:
            raise LoginInputError("A cookie value is too large.")
        # RFC 6265 cookie-octet: visible ASCII, excluding quote, comma and backslash.
        if any(not (ord(c) == 33 or 35 <= ord(c) <= 43 or 45 <= ord(c) <= 58
                    or 60 <= ord(c) <= 91 or 93 <= ord(c) <= 126) for c in cookie_value):
            raise LoginInputError("The copied request contains an invalid cookie value.")
        parsed[name] = cookie_value
    missing = [name for name in REQUIRED_COOKIES if name not in parsed]
    if missing:
        raise LoginInputError("Missing required cookies: " + ", ".join(missing) + ".")
    return {name: parsed[name] for name in ALLOWED_COOKIES if name in parsed}


def extract_cookies(command):
    """Parse one exact /web/config GET request; never execute it or contact a server."""
    args = _normalize_command(command)
    if not args or args[0] != "curl":
        raise LoginInputError("Copy the request using Copy as cURL (POSIX/bash).")
    urls = []
    cookie_strings = []
    i = 1
    while i < len(args):
        arg = args[i]
        if arg in NO_ARGUMENT_FLAGS:
            i += 1
            continue
        option, value = arg, None
        if arg.startswith("--") and "=" in arg:
            option, value = arg.split("=", 1)
        elif len(arg) > 2 and arg[:2] in ("-H", "-b", "-X"):
            option, value = arg[:2], arg[2:]
        if option in ("-H", "--header", "-b", "--cookie", "-X", "--request", "--url"):
            if value is None:
                i += 1
                if i >= len(args):
                    raise LoginInputError("A copied cURL option is missing its value.")
                value = args[i]
            if option in ("-H", "--header"):
                name, separator, content = value.partition(":")
                if not separator or not TOKEN.fullmatch(name):
                    raise LoginInputError("The copied request contains a malformed header.")
                if name.lower() == "cookie":
                    cookie_strings.append(content.strip(" "))
            elif option in ("-b", "--cookie"):
                cookie_strings.append(value)
            elif option in ("-X", "--request"):
                if value != "GET":
                    raise LoginInputError("Only a /web/config GET request is accepted.")
            else:
                urls.append(value)
        elif arg.startswith("-"):
            raise LoginInputError("The copied request uses an unsupported cURL option.")
        else:
            urls.append(arg)
        i += 1
    if urls != [EXPECTED_URL]:
        raise LoginInputError("Copy exactly one request to https://messages.google.com/web/config.")
    if len(cookie_strings) != 1:
        raise LoginInputError("The request must contain exactly one Cookie header or --cookie value.")
    return _parse_cookie_string(cookie_strings[0])


def write_private_json(output, cookies):
    """Create a new 0600 file, refusing existing paths and symlink directories."""
    if os.name != "posix" or not hasattr(os, "O_NOFOLLOW"):
        raise LoginInputError("Run this helper on macOS or Linux to create a protected file.")
    path = Path(os.path.abspath(output))
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    parent_fd = os.open(path.anchor, flags)
    try:
        for part in path.parts[1:-1]:
            next_fd = os.open(part, flags, dir_fd=parent_fd)
            os.close(parent_fd)
            parent_fd = next_fd
        fd = os.open(path.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=parent_fd)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(cookies, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(parent_fd)


def read_input(args):
    if args.clipboard:
        if sys.platform != "darwin":
            raise LoginInputError("--clipboard is available only on macOS; use --input for a local file.")
        # This is the sole subprocess invocation. The copied cURL is never an argument.
        result = subprocess.run(["/usr/bin/pbpaste"], check=True, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=10)
        data = result.stdout
    else:
        with args.input.open("rb") as stream:
            data = stream.read(MAX_INPUT_BYTES + 1)
    if len(data) > MAX_INPUT_BYTES:
        raise LoginInputError("The copied request is too large.")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        raise LoginInputError("The copied request must be UTF-8 text.") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--clipboard", action="store_true", help="Explicitly read the macOS clipboard")
    source.add_argument("--input", type=Path, help="Read a local file containing the copied cURL")
    parser.add_argument("--output", type=Path,
                        default=Path.home() / "Downloads" / "RCS Bridge Login.json",
                        help="New local JSON file (default: ~/Downloads/RCS Bridge Login.json)")
    args = parser.parse_args(argv)
    try:
        cookies = extract_cookies(read_input(args))
        write_private_json(args.output, cookies)
    except LoginInputError as exc:
        print("Error: " + str(exc), file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError):
        print("Error: Could not read the input or create a new protected output file. "
              "Choose an existing folder without symlinks and a filename that does not exist.", file=sys.stderr)
        return 1
    print("Created the local login JSON with owner-only permissions (0600).")
    print("It contains a sensitive Google session in plaintext. Import it only into your RCS Bridge Test app, "
          "then delete the transfer file. Never send it to chat, GitHub, or a support log.")
    if args.clipboard:
        print("The copied request is still on your clipboard. Replace it by copying ordinary text.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
