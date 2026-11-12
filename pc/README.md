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
