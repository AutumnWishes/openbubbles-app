<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# Pair Google Messages with the RCS test

This first test uses the same Google-account pairing method documented by
[mautrix-gmessages](https://docs.mau.fi/bridges/go/gmessages/authentication.html).
Prepare the login file on your Mac, import it on the Fold, and approve the pairing
in Google Messages. No always-on computer or separate bridge server is required
by this test's design. Actual pairing and operation on the same phone still need
device testing.

Google does not support Google sign-in inside Android WebViews. This test uses a
local file import instead of pretending that a normal Google sign-in button can
grant the unofficial bridge access. The file contains **sensitive Google session
cookies in plaintext**, not a limited RCS-only access token. Keep it on your own
devices, transfer it locally, and delete the transfer copies after importing.
Never attach it to chat, a GitHub issue, a support log, or a screenshot.

## Prepare the file once on your Mac

Use Python 3 and a local checkout or extracted source ZIP of this repository.
Open Terminal in the repository folder. Keep Google Messages as the Fold's
default messaging app, with RCS connected and the intended Google account signed
in. Leave Google Messages for web closed on other computers during this test.

1. In **Firefox**, open a **New Private Window**. Use a private window containing
   only the Google account used by Google Messages on the Fold.
2. Open this exact address and sign in normally on Google's page:

   ```text
   https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config
   ```

3. Open Firefox's **Developer Tools → Network** (`Option+Command+E` on a Mac),
   then reload the page. Find the request whose address is exactly
   `https://messages.google.com/web/config` (a trailing `?pli=1` is also accepted).
4. Right-click that request and select **Copy Value → Copy as cURL**. Firefox
   versions may label it **Copy → Copy as cURL**. Do **not** run the copied cURL
   command or paste it into chat. Stay on this page; do not pair the browser.
5. In Terminal, type this command manually, or paste it before copying the Firefox
   request again. Copying the command below replaces the request on your clipboard.
   After copying the request, switch back to Terminal and press Return on the
   already-entered command without pasting again. Run this command. It reads the clipboard only because
   `--clipboard` is explicitly selected:

   ```sh
   python3 tools/rcs_bridge_harness/auth/prepare_login.py --clipboard
   ```

   The result is `~/Downloads/RCS Bridge Login.json`, with owner-only file
   permissions. The helper never runs cURL, evaluates shell commands, or makes
   network requests. It exports only the cookies needed by the bridge and does
   not print their values.

6. Replace the clipboard contents by copying ordinary text. Close that private
   browser window without signing out; signing out can invalidate the session.

Firefox is recommended because upstream documents problems with browsers that
bind session cookies to the browser device. Do not assume that a Chrome cookie
export will work.

If the output already exists, the helper refuses to overwrite it. Delete the
previous transfer file yourself if it is no longer needed, or choose a new name:

```sh
python3 tools/rcs_bridge_harness/auth/prepare_login.py --clipboard --output "$HOME/Downloads/RCS Bridge Login 2.json"
```

Alternatively, save the copied request as a local plain-text file and use
`--input`. Use this only for a file on your own computer; delete that file too
after import:

```sh
python3 tools/rcs_bridge_harness/auth/prepare_login.py --input "$HOME/Downloads/Google Messages Request.txt" --output "$HOME/Downloads/RCS Bridge Login.json"
```

## Import and pair on the Fold

1. Transfer `RCS Bridge Login.json` locally to your Fold, such as by USB file
   transfer. Do not upload it to the repository or send it through this chat.
2. Open the **RCS Bridge Test** app and tap **Import sign-in file**.
   Select the JSON file with Android's system file picker.
3. Tap **Pair Google Messages**. The test app displays a verification symbol. Open Google
   Messages on the same Fold and choose the matching symbol in its pairing
   prompt, then return to the test app.
4. Wait for the test app to report a completed connection. Importing a file or
   displaying a pairing symbol alone does not prove successful pairing.
5. Delete the transferred JSON from both the Fold and your Mac. Delete any local
   request text file you created. The test app keeps its paired session in
   encrypted app storage; it does not need the transfer file afterward.

The first test still requires you to intentionally tap **Send RCS** in the test
app and verify the recipient receives the message **as RCS**. Check each SIM separately.
Nothing in the preparation helper sends a message.

If pairing fails, share only the app's sanitized error message. Never share the
login file or the copied cURL request. If Google refuses the session or requires
login again, prepare a fresh file in a new private window. Rooting the phone,
changing the default SMS app, or disabling RCS is not part of this procedure.

## Offline helper checks

All test credentials are invented. These checks do not read your clipboard or
access Google:

```sh
python3 -m unittest discover -s tools/rcs_bridge_harness/auth -p 'test_*.py' -v
```

The helper and tests are licensed `AGPL-3.0-or-later`; see the harness license.
