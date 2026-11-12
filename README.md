# Jarvis (personal Android assistant)

A voice assistant for one person. He talks like a calm British butler, calls you "sir"
(or "сер"), understands English and Ukrainian and answers in the language you use.
When a timer ends or a longer task is done, he **rings your phone** with a call screen;
answer and he tells you the news.

It uses the free Gemini API (and optionally a free Groq key as a backup).

## Install on your phone

1. On the phone, open https://github.com/Dialorsd/Project-J/releases/latest and tap **jarvis.apk**
   (direct link: https://github.com/Dialorsd/Project-J/releases/download/latest/jarvis.apk).
2. When Android asks, allow your browser to **install unknown apps** (Settings → Apps → your browser →
   Install unknown apps → Allow). This is needed because the app is not from the Play Store.
3. Open the downloaded file and tap **Install** (or **Update**). New builds install over the old one;
   your settings and chat are kept.
4. If Play Protect warns about an unknown app, choose **Install anyway** (it is your own app).

## Add your key

On first start the app opens **Settings**:

1. Go to **aistudio.google.com**, sign in, tap **Get API key**, create a key and copy it.
2. Paste it into "Gemini API key" and tap **Save**.
3. Optional: a Groq key (console.groq.com → API Keys) is used only if Gemini is over its limit or down.

Keys are stored only on the phone, in the app's private storage. They are never put in this repository.

## Permissions it asks for, and why

- **Microphone**: to hear you when you tap the mic button. You can always type instead.
- **Notifications**: needed for Jarvis to "call" you.
- **Full-screen notifications** (Android 14+): lets the call screen appear over the lock screen.
  If it is off, the app shows a banner with an **Allow** button.
- **Alarms & reminders** (exact alarms): so timers ring on time even when the app is closed.
- **Run at startup**: timers are restored after the phone restarts.

## Using it

- Tap the round mic button and speak, or type. The **EN / UK / AUTO** chip at the top chooses the
  speech-recognition language.
- "Set a timer for 10 minutes for the pasta" → a countdown appears; when it ends, Jarvis calls.
- "Write me a detailed plan for …" → he works on it in the background and calls when it is done.
  The full result is in the chat.
- Settings: voice (Gemini voice / phone voice / muted), humour slider, model names.

## Known limits of version 1

- No wake word ("Hey Jarvis") and no continuous conversation: tap the mic each time.
- He cannot browse the internet, read messages or control other apps.
- Background tasks are lost if the phone restarts or Android kills the app mid-task.
- Gemini voice is used for English only; Ukrainian uses the phone's own voice
  (install a Ukrainian voice in the phone's text-to-speech settings for best results).
- Free quotas are limited per day; when used up, Jarvis says so (or uses Groq if you added a key).
- Speech recognition uses the phone's built-in service (usually Google) and may need internet.

## Note about the signing key

The key that signs the APK (`keystore/jarvis.jks`) and its password are committed to this repository
on purpose, so every build can update the previous one. Because this repository is public, anyone could
sign an APK with that key that your phone would accept as an update, so only install APKs downloaded
from this repository's Releases page.

## For developers

Kotlin + Jetpack Compose, single `app` module, no other frameworks. Built by GitHub Actions
(`.github/workflows/build.yml`) on every push to `main`; the APK is attached to the `latest` release.
Model IDs are in `app/src/main/java/com/dialorsd/jarvis/Config.kt` and can be changed in Settings.
