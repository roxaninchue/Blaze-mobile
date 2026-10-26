---
name: reverse-engineer-anything
title: "REA: Reverse Engineer Anything"
description: Understand how an app, feature, website, Android APK, native binary, or JavaScript/Electron app works from the shipped artifact - static analysis first with REA (rea-agents) in Blaze's Linux terminal, JADX/apktool for APKs, then an evidence-backed explanation and an offer to build something similar. Use when the user asks how an app or feature works under the hood, to decompile or inspect an APK/binary/ASAR, or to compare versions. Only software the user owns or may inspect.
tags: [reverse-engineering, apk, android, binary, javascript, electron, decompile, analysis, rea]
version: 1
---

# REA: Reverse Engineer Anything

Adapted from REA by morluto (github.com/morluto/rea, MIT). Tools: `run_shell` (Linux), `linux_install`,
`file_read` / `file_write`, `download_file`, `terminal_jobs`.

## Guardrails (check first, every time)

- Only analyse software the user owns, wrote, or is clearly allowed to inspect (their own app, open source, a
  file they supplied for interoperability or learning). If that's unclear, ask one short question.
- Never help bypass DRM, licence checks, paywalls, anti-cheat or copy protection; never crack, patch out
  checks, extract keys or credentials, or build/modify malware. Say no plainly and offer a legitimate route
  (official API, the vendor's docs, building your own version).
- Static first. Never run an unknown binary or APK to "see what it does".

## 1. Pick the target

Get one concrete target: a path in Linux, a downloaded file, an extracted folder, or a URL the user gave.
"My app" with no name: ask which app. Never pick an example app yourself. For a website, prefer its own
public JS bundles (save them with `download_file`) over guessing.

## 2. Make sure the tools are there

- Node.js 22+ and npm: `node -v`. Missing or too old: `linux_install` nodejs + npm (or the distro's
  package manager in `run_shell`). If the distro can't give Node 22+, say so and fall back to step 4.
- Run REA through npx, no global install: `npx -y rea-agents@latest <command> ... --json`.
- Android: Java 17+ (`java -version`), then JADX and/or apktool if available (`linux_install` jadx apktool,
  or the release jar). REA's Android tools need a JADX MCP jar set in `REA_JADX_MCP_JAR`; check with
  `npx -y rea-agents@latest doctor --provider jadx --json`.
- Long runs: `run_shell` background=true, then `terminal_jobs`. Save big JSON to a file, never paste it.

## 3. Route by target (static analysis)

- JS / Electron app (extracted folder or `.asar`):
  `npx -y rea-agents@latest analyze-javascript-application /abs/path --json > ~/rea/app-evidence.json`,
  then a summary view: `{"source":{"kind":"evidence-file","path":"app-evidence.json"},"view":{"kind":"summary"}}`
  piped to `npx -y rea-agents@latest inspect-analysis-view`. Page modules (`{"kind":"page","collection":"modules","offset":0,"limit":32}`)
  only when the summary leaves a specific question.
- Package / archive (APK, ZIP, IPA, DMG, PyInstaller exe): `npx -y rea-agents@latest inspect-artifact <file> --detail summary --evidence_output ~/rea/ev.json --json`.
- Android APK code: REA Android tools when `doctor` says JADX is ready; otherwise
  `jadx -d ~/rea/<app>-src <apk>` (Java sources) and/or `apktool d -o ~/rea/<app>-res <apk>` (manifest, resources, smali),
  then `grep -rn` for the feature's strings, activities, intents, endpoints and permissions.
  APK signing: `npx -y rea-agents@latest inspect-apk-signature <apk> --json`.
- Native binary (ELF/PE/Mach-O): start with `inspect-artifact` or `file`/`strings`/`readelf`; deep decompilation
  needs Ghidra/Hopper/IDA, which REA never installs - say so instead of installing big engines unasked.
- Nothing fits or REA fails twice on the same input: stop retrying, use plain tools (`unzip -l`, `strings`, `grep`, jadx) and say what REA couldn't do.

## 4. Work the question, not the whole app

Turn the request into 1-5 questions ("where does the share sheet get its targets?"). Find the entry point
(a string, screen, endpoint, class name), follow it through code and config to the result, and stop when each
question is answered or clearly unknown. Don't re-run an identical analysis.

## 5. Report

Plain language, short. For each question: what it does, the **evidence** (file path, class/function, module,
Evidence ID from REA), and the **limits** - static analysis didn't observe it running; reflection, server-side
logic, obfuscation and dynamic loading stay unknown. Separate what you saw from what you infer.
Then offer the next step: "Want me to build a similar feature?" - and if yes, write it fresh in the user's
own project with normal coding tools (never copy proprietary code wholesale).
