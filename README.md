# OpenBubbles

## Experimental native SMS fork

This fork adds an experimental **Phone SMS** screen that uses the Android phone's
SIM. The automatic **Build SMS Prototype** workflow builds a separate test app
from the same SMS code, without Apple activation or iMessage. It does not yet
support MMS, RCS, or becoming the default SMS app.

To download a test build, open **Actions → Build SMS Prototype**, select a
successful run, and download **SMS-Prototype-arm64-test** under **Artifacts**.
Pushes to `feature/native-android-sms` and `rustpush` build automatically when
relevant files change. **Run workflow** also allows a manual build.

[Build instructions, current limitations, and validation](docs/native-sms-development.md)

The **Build OpenBubbles RCS Alpha** workflow now targets the full app with a
shared iMessage, local SMS and Google Messages RCS inbox. Its artifact is
**OpenBubbles-RCS-Alpha-arm64-test** and installs as **OpenBubbles RCS Alpha**
alongside the official app and the earlier SMS Alpha. The setup screen offers
**Use SMS and RCS without iMessage setup**. Fresh Apple activation remains a
separate, unverified requirement; a successful APK build does not establish
working iMessage. Every installation must pair its own Google Messages account;
no login JSON, cookies or saved pairing are distributed in source or APKs.

[RCS Alpha setup, routing rules and current limits](tools/full_app_build/RCS_ALPHA.md)

[Earlier SMS Alpha build and phone-test instructions](docs/full-app-alpha.md)

## Experimental Google Messages RCS bridge test

The separate **Build RCS Bridge Test** workflow packages a paired Google Messages
client for testing RCS through Google Messages on the same Android phone. It
does not replace the working SMS Alpha or become the default messaging app.
The first test uses a local login-file import and needs phone validation.

[RCS bridge test, pairing, and download instructions](tools/rcs_bridge_harness/README.md)

The original OpenBubbles project description follows.

OpenBubbles is an open-source and cross-platform ecosystem of apps aimed to bring Apple platform services to Android and Windows! With OpenBubbles, you'll be able to send messages, media, and much more to your friends and family.

**Please note that OpenBubbles requires access to a Mac and an Apple ID to function!

Key Features:

- Send/receive emoji reactions 
- Send formatted messages (bold, italic, etc)
- Edit messages
- Unsend messages 
- Call your friends on FaceTime
- Answer calls from your friends on FaceTime
- See friends' locations on FindMy
- Join and Sync iCloud Shared Albums
- See typing indicators
- Receive stickers
- Create and manage group chats
- Add an icon to personalize your group chat 
- Send images and videos
- Forward SMS and MMS to/from connected Macs or other devices with OpenBubbles 

If you need help setting up the app, have any issues or feature requests, or just want to come hang out, feel free to join our Discord, linked below! We hope you enjoy using the app!

## Useful links

* Our Website: [here](https://openbubbles.app)
* Discord: [here](https://discord.gg/98fWS4AQqN)!
    - We highly encourage users to join to get in direct communication with the developers and community
* GitHub: [here](https://github.com/OpenBubbles)
    - Please submit any issues with the app here so we can properly track them! Remember to search before opening a ticket :)

## Getting Started

[Quickstart](https://openbubbles.app/quickstart.html)
