# Jarvis (personal Android assistant)

A voice assistant for one person. He talks like a calm British butler, calls you "sir"
(or "сер"), understands English and Ukrainian and answers in the language you use.
When a timer ends or a longer task is done, he **rings your phone** with a call screen;
answer and he tells you the news.

It uses the free Gemini API (and optionally a free Groq key as a backup).

What he can do (version 3):

- **Live conversation** (new): tap **LIVE CONVERSATION** and just talk, hands-free, like a phone call. You can
  interrupt him while he speaks. Works with the screen off; a notification with **End** shows while it runs.
- **Morning call** (new): at the time you choose he rings and reads a short briefing: date, weather, today's
  events, timers, messages from your PC overnight and open notes.
- **Reminders that nag** (new): "remind me at 5 pm to call mum". If you decline or miss the call he rings again
  (every 10 min, up to 3 times by default) until you tap **Done**.
- **Notes and lists** (new): "add milk to my shopping list", "what's on my shopping list?". See them under **NOTES**.
- **Heads-ups** (new): a notification before calendar events; optional low-battery warning.
- **Widget, Quick Settings tile, headset button** (new): see "Quick access" below.
- Talk (English or Ukrainian), with a choice of Gemini voices (Settings → Voice → Preview to try them).
- **Timers** ("call me in 10 minutes"), and **background tasks** ("write me a plan for…") — he calls you when done.
- **Look things up on the web** (Google Search through Gemini; free, about 500 searches a day). Sources are listed under the answer.
- **Remember** things ("remember that my sister's birthday is 3 May"); see or delete them in Settings → Memory.
- **Calendar**: "what's on tomorrow?", "add dentist on Friday at 3 pm".
- **Navigation**: "take me to the train station" opens Google Maps navigation.
- **PC link**: your computer (or Claude Code) can make Jarvis call you. See [pc/README.md](pc/README.md).

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

- **Microphone**: to hear you when you tap the mic button or start Live mode. You can always type instead.
- **Microphone in the background** (Live mode only, Android 14+ "microphone" foreground service): keeps Live mode
  working with the screen off. It only runs after you start Live mode and always shows a notification with **End**.
- **Change audio settings**: Live mode switches to speakerphone / your headset like a phone call.
- **Notifications**: needed for Jarvis to "call" you.
- **Full-screen notifications** (Android 14+): lets the call screen appear over the lock screen.
  If it is off, the app shows a banner with an **Allow** button.
- **Alarms & reminders** (exact alarms): so timers ring on time even when the app is closed.
- **Run at startup**: timers are restored after the phone restarts, and the PC link reconnects.
- **Calendar** (optional): to read your upcoming events and add new ones. Asked for in Settings or the first time you ask him to add an event.
- **Background connection** (only if PC link is on): an always-on silent notification "Jarvis is listening for your PC".

## Using it

### Live conversation

1. Tap **LIVE CONVERSATION** under the orb (allow the microphone if asked).
2. Talk normally. The orb shows LISTENING, THINKING (doing something, e.g. setting a timer) or SPEAKING.
   Speak over him to interrupt. Both sides of the conversation are written into the chat.
3. **MUTE** pauses the microphone, **END** (or End in the notification) stops. He also stops after 10 silent minutes.

If he keeps interrupting himself on the loudspeaker, turn off Settings → Live mode → "Allow interrupting him",
or use headphones. If Live mode cannot start, the normal mic button still works exactly as before.

### Quick access

- **Widget**: long-press the home screen → Widgets → Jarvis. **TALK** opens the app listening, **LIVE** starts
  Live mode; the next timer is shown underneath.
- **Quick Settings tile**: pull down the quick settings, tap the pencil, drag **Jarvis Live** in. Tapping it opens
  Jarvis in Live mode (tap again to end).
- **Headset button**: while the app is open, a press starts/stops listening; in Live mode it mutes/unmutes.
  It is not captured while the app is closed.

### Morning call

Settings → Morning call: turn it on, set the time (e.g. 07:30), the days, and your city (tap CHECK). "Try the
morning call now" tests it. Weather comes from Open-Meteo (free, no account, no location permission).

### Diagnostics

Settings → Diagnostics has TEST buttons for the Gemini text model, the Gemini voice, the Live connection, web
search, weather and a test call in 5 seconds. Each shows OK or the exact error; **COPY** copies it to send to the
developer.

### Classic mode

- Tap the round mic button and speak, or type. The **EN / UK / AUTO** chip at the top chooses the
  speech-recognition language.
- "Set a timer for 10 minutes for the pasta" → a countdown appears; when it ends, Jarvis calls.
- "Write me a detailed plan for …" → he works on it in the background and calls when it is done.
  The full result is in the chat.
- Settings: voice (Gemini voice / phone voice / muted), humour slider, model names.

## Battery

- **Live mode**: uses the microphone and the network continuously, but only while it is running (notification
  shown). Expect roughly the drain of a phone call. It ends itself after 10 silent minutes.
- **Morning call, timers, reminders, event heads-ups**: alarms only (no background loops). The event heads-up
  wakes the phone at most once per event plus a check every 6 hours.
- **Low battery warning** (off by default): a system broadcast while the app is in memory, plus a half-hourly
  check that never wakes the phone by itself.
- **Widget**: updated only when timers change. **Tile / headset button**: nothing runs in the background.
- **PC link**: keeps a connection open (see below).

## Known limits

- No wake word ("Hey Jarvis"): start Live mode or tap the mic.
- Live mode uses Google's free Live models (currently `gemini-3.8-live`, backup `gemini-3.1-flash-live-preview`;
  editable under Settings → Advanced). Free-tier limits are not published per model; when they are used up
  Jarvis says so and the normal mic button still works. Long Live sessions reconnect every ~10 minutes
  (Google's connection limit) and resume the conversation; if resuming fails a fresh session starts.
- In Live mode, timers, notes, calendar and so on are done through the same actions as in normal mode; background
  tasks still call you when they finish.
- He cannot read messages or contacts or control other apps. Web search answers questions; it does not open web pages for you.
- Memories, notes and lists, recent chat and calendar events are sent to Gemini with your questions (that is how he knows them).
  In Live mode your voice is streamed to Google while the session runs.
- Background tasks are lost if the phone restarts or Android kills the app mid-task.
- Push-to-talk: Gemini voice is used for English only; Ukrainian uses the phone's own voice
  (install a Ukrainian voice in the phone's text-to-speech settings for best results). Live mode speaks both.
- The morning call and reminders need "Alarms & reminders" and full-screen notifications allowed to ring on time
  over the lock screen. The headset button works only while the app is open or Live mode runs.
- PC link keeps a connection open and uses some extra battery. Anyone who knows your topic can ring your phone; keep it secret.
- Free quotas are limited per day; when used up, Jarvis says so (or uses Groq if you added a key).
- Speech recognition uses the phone's built-in service (usually Google) and may need internet.

## Note about the signing key

The key that signs the APK (`keystore/jarvis.jks`) and its password are committed to this repository
on purpose, so every build can update the previous one. Because this repository is public, anyone could
sign an APK with that key that your phone would accept as an update, so only install APKs downloaded
from this repository's Releases page.

## For developers

Kotlin + Jetpack Compose, single `app` module; the only library besides AndroidX is OkHttp (WebSocket for
the Live API). No DI or navigation frameworks. Weather: [Open-Meteo](https://open-meteo.com/) (CC BY 4.0). Built by GitHub Actions
(`.github/workflows/build.yml`) on every push to `main`; the APK is attached to the `latest` release.
Model IDs are in `app/src/main/java/com/dialorsd/jarvis/Config.kt` and can be changed in Settings.
