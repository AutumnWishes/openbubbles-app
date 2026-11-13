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

The **Build OpenBubbles SMS Alpha** workflow targets the full app with the same
SMS screen. Its artifact is **OpenBubbles-SMS-Alpha-arm64-test** and installs as
**OpenBubbles (Alpha)** alongside the official app. The setup screen offers
**Use Phone SMS without iMessage setup**. Fresh Apple activation is a separate,
unverified requirement; a successful APK build does not establish working iMessage.

[Full alpha build and phone-test instructions](docs/full-app-alpha.md)

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
