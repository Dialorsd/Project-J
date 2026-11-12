# PC link: let your computer make Jarvis call you

Your computer sends a short message through the free service **ntfy.sh**; Jarvis on your phone
receives it and calls you ("PC: ..."). No accounts, nothing to install except these small scripts.

> **Your topic is a password.** Anyone who knows the topic name can make your phone ring and read
> your pings. Never share it, never paste it into GitHub or a public chat, and never put secrets
> (passwords, keys) in a ping. This repository is public, so the topic is stored only in a file in
> your home folder, never here. If it leaks: in the app tap **NEW TOPIC**, Save, and set it again on the PC.

## 1. On the phone

1. Jarvis → **Settings** → **PC LINK**.
2. Turn **PC link** on. Status should say "connected" after a few seconds.
3. Tap **COPY** to copy the topic (it looks like `jarvis-k3m9...`). Send it to your PC privately
   (for example in an email to yourself), or just type it.

The phone keeps a small connection open while PC link is on; this uses a little extra battery.

## 2. Windows

1. Open **PowerShell** (Start menu → type `PowerShell` → open it).
2. Download the scripts into a folder called `jarvis` in your user folder. Copy and paste this, then press Enter:

   ```powershell
   mkdir $HOME\jarvis -Force; cd $HOME\jarvis; foreach ($f in 'jarvis-ping.ps1','jarvis-run.ps1') { Invoke-WebRequest "https://raw.githubusercontent.com/Dialorsd/Project-J/main/pc/$f" -OutFile $f }
   ```

3. Save your topic (replace `jarvis-xxxx` with the topic from the app):

   ```powershell
   powershell -ExecutionPolicy Bypass -File $HOME\jarvis\jarvis-ping.ps1 -SetTopic jarvis-xxxx
   ```

4. Test it. Your phone should ring within a few seconds:

   ```powershell
   powershell -ExecutionPolicy Bypass -File $HOME\jarvis\jarvis-ping.ps1 "Hello from my PC" -Title "Test"
   ```

   Add `-Quiet` to get only a notification instead of a call.

5. Run any long command and get called when it ends (success or failure, with how long it took):

   ```powershell
   powershell -ExecutionPolicy Bypass -File $HOME\jarvis\jarvis-run.ps1 python my_script.py
   ```

### Claude Code on Windows (call me when Claude is done or needs me)

1. Print the ready-made settings for your PC:

   ```powershell
   powershell -ExecutionPolicy Bypass -File $HOME\jarvis\jarvis-ping.ps1 -PrintHook
   ```

2. Open (or create) the file `C:\Users\<you>\.claude\settings.json` in Notepad
   (`notepad $HOME\.claude\settings.json`).
   - If the file is empty or new, paste the whole printed text and save.
   - If it already has content, add the `"hooks": { ... }` part inside the outer `{ }`, with a comma
     after the previous entry. If it already has a `"hooks"` section, add the `"Stop"` and
     `"Notification"` entries to it.
3. Restart Claude Code. You will now get a call when Claude finishes a reply, and when it needs your
   permission or input. The message includes the project folder name.

It looks like this (your path will differ):

```json
{
  "hooks": {
    "Stop": [
      { "hooks": [ { "type": "command", "command": "powershell.exe", "args": ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:/Users/you/jarvis/jarvis-ping.ps1", "-Hook"], "timeout": 30 } ] }
    ],
    "Notification": [
      { "matcher": "permission_prompt|elicitation_dialog|agent_needs_input", "hooks": [ { "type": "command", "command": "powershell.exe", "args": ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:/Users/you/jarvis/jarvis-ping.ps1", "-Hook"], "timeout": 30 } ] }
    ]
  }
}
```

Too many calls? Delete the `"Stop"` entry to be called only when Claude needs you.

## 3. macOS / Linux

1. Open **Terminal** and download the scripts:

   ```sh
   mkdir -p ~/jarvis && cd ~/jarvis && for f in jarvis-ping.sh jarvis-run.sh; do curl -fsSLO "https://raw.githubusercontent.com/Dialorsd/Project-J/main/pc/$f"; done && chmod +x *.sh
   ```

2. Save your topic: `~/jarvis/jarvis-ping.sh --set-topic jarvis-xxxx`
3. Test: `~/jarvis/jarvis-ping.sh "Hello from my Mac" "Test"` (add `--quiet` first for notification only).
4. Long commands: `~/jarvis/jarvis-run.sh make build`
5. Claude Code: run `~/jarvis/jarvis-ping.sh --print-hook` and paste the result into
   `~/.claude/settings.json` the same way as described for Windows, then restart Claude Code.

## Notes

- Instead of the config file you can set the environment variable `JARVIS_TOPIC` (and `JARVIS_SERVER`).
- The free ntfy.sh server allows about 250 messages a day and keeps messages for about 12 hours, so
  pings sent while the phone is offline arrive when it reconnects (if within that time).
- You can use your own ntfy server: set it in the app (Server) and with `-Server` / the second
  argument of `--set-topic`.

---

# PC remote (new in v4): ask Jarvis about your PC, or tell it to do something

Examples: "how is my PC?", "is Blender still running?", "lock my PC", "call me when the render finishes"
(watch a program), "shut down the PC when the download is done", "run backup".

The phone sends a command to a small helper on the PC, **jarvis-listen**, through ntfy. The PC replies on the
PC link topic, and Jarvis says or writes the answer. **PC link must be on in the app.**

## How it is protected (please read)

- The phone **never sends commands to type into a terminal**. It only sends a short command **name** such as
  `status` or `lock`. The PC only does things that are switched on in **its own** settings file
  (`.jarvis-pc.json` in your user folder), which the phone cannot change.
- Every request is **signed** with a secret key that only your phone and your PC know (HMAC-SHA256 over the
  time, a random one-time number and the request). The PC ignores anything with a wrong signature, anything older
  than **60 seconds** (so check that the PC and phone clocks are correct) and anything it has already seen.
  Someone who learns your ntfy topics still cannot make your PC do anything.
- Commands go to their own random topic, separate from the ping topic. Replies are signed too.
- **Off until you switch them on** (in `.jarvis-pc.json`): `sleep`, `shutdown`, `restart`, `shutdown_when_done`
  and `run` (your own named commands). **On by default:** `status`, `lock`, `watch_exit` (tell me when a program
  exits) and `cancel_shutdown`.
- Jarvis always asks "Shall I, sir?" before shutdown, restart or sleep and only sends it after you say yes.
  Shutdown and restart wait **60 seconds** and can be cancelled ("cancel the shutdown", or `shutdown /a`).
- Keep `.jarvis-pc.json` private: it contains the secret. Never upload it or paste the pairing code in public.
  If the code leaks, tap **PAIR AGAIN** in the app and pair the PC again (the old secret stops working).
- Status replies (CPU, memory, program names) travel through ntfy.sh like pings do: anyone who knows the
  ping topic could read them, but not fake them.

## Windows (start here)

1. Phone: Jarvis → **Settings** → **PC LINK**: make sure PC link is on, then tap **PAIR PC**.
   A pairing code appears (shown only once). Tap **COPY CODE** and send it to your PC privately (for example
   an email to yourself), or keep the phone next to you.
2. PC: open **PowerShell** (Start → type `PowerShell`). Tap **COPY COMMAND** in the app (or type this), paste it
   and press Enter:

   ```powershell
   mkdir $HOME\jarvis -Force; cd $HOME\jarvis; Invoke-WebRequest https://raw.githubusercontent.com/Dialorsd/Project-J/main/pc/jarvis-listen.ps1 -OutFile jarvis-listen.ps1; powershell -ExecutionPolicy Bypass -File .\jarvis-listen.ps1 -Pair
   ```

   When it asks, paste the pairing code (nothing is shown while you paste) and press Enter.
3. Start it now and at every login, hidden (adds a shortcut to your Startup folder):

   ```powershell
   powershell -ExecutionPolicy Bypass -File $HOME\jarvis\jarvis-listen.ps1 -InstallStartup
   ```

4. Test: on the phone, Settings → Diagnostics → **PC remote status** → TEST, or ask "how is my PC?".

To switch on more commands, open the settings file in Notepad: `notepad $HOME\.jarvis-pc.json`, change `false` to
`true` for the ones you want, save, and restart the listener (`jarvis-listen.ps1 -Stop`, then `-InstallStartup`
again, or sign out and in). Your own commands go under `"commands"`, one per line, for example:

```json
  "enable_run": true,
  "commands": {
    "backup": "C:\\Users\\you\\backup.bat",
    "night": "C:\\Tools\\night-mode.exe --on"
  }
```

(Inside the quotes, write every `\` twice.) Then say "run backup". Jarvis can only start commands that are in this list.

Other options: `-Stop` stops the running listener, `-UninstallStartup` stops it starting at login,
`-ShowConfig` shows what is switched on. The log is `$HOME\.jarvis-pc.log`.

Notes for Windows: "lock" and "sleep" work because the listener runs in your own logged-in session.
"Sleep" may hibernate instead if hibernation is set up that way in Windows. "Program names" are as in Task
Manager → Details, without `.exe` (e.g. `blender`, `python`). This script has **not been tested on a real
Windows PC yet**; if something fails, look in `$HOME\.jarvis-pc.log` and send the error.

## macOS / Linux

```sh
mkdir -p ~/jarvis && cd ~/jarvis && curl -fsSLO https://raw.githubusercontent.com/Dialorsd/Project-J/main/pc/jarvis-listen.sh && chmod +x jarvis-listen.sh
~/jarvis/jarvis-listen.sh --pair        # paste the code from the app
~/jarvis/jarvis-listen.sh               # run it (keep the terminal open), or start it at login:
(crontab -l 2>/dev/null; echo "@reboot $HOME/jarvis/jarvis-listen.sh >/dev/null 2>&1") | crontab -
```

The settings file is `~/.jarvis-pc.json` (same switches as on Windows; own commands are run with `sh -c`).
Shutdown/restart need permission to run `shutdown` (root on Linux; `sudo` without a password on macOS),
otherwise Jarvis reports that it failed. Needs only bash, curl and openssl.
