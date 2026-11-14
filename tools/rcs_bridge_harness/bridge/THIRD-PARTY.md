# Google Messages bridge source

This isolated prototype links `go.mau.fi/mautrix-gmessages/pkg/libgm` from
<https://github.com/mautrix/gmessages>, pinned to commit
`b826f892d87274d465fb5b903870a831c12714ad` (2026-11-13).

That client is licensed under GNU AGPL version 3 or later. The Android prototype
and wrapper in this harness are distributed under AGPL-3.0-or-later; see the
harness `LICENSE`. The parent OpenBubbles repository retains its own license.
The original source, notices, and generated protobuf code remain in the build's
pinned upstream checkout. A source archive is included with the test artifact.

The reproducible patch in `patches/reject-multiple-phones.patch` refuses to
automatically choose among multiple Google Messages phones and makes the initial
long-poll wait honor cancellation. `patches/no-retry-and-auth-snapshot.patch`
disables automatic HTTP retry after a server error and protects renewed auth
tokens while taking a session snapshot. Its offline tests exercise the actual
upstream send path against a fake transport. The prototype does not patch
authentication verification, message encryption, or the phone confirmation
challenge.

The linked-device protocol is unofficial. A successful compile is not evidence
that Google pairing, same-phone operation, dual SIM, or background delivery work.
No Google account sessions or credentials are included in the repository or APK.
