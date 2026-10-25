# The 33 ideas: verdicts (v0.33.1)

Each idea was checked against the v0.33.0 code. **Built** = in v0.33.1. **Already there** = Blaze does this (or most of it) today. **Not now / No** = why, and what would change the answer.

| # | Idea | Verdict | Why / where |
|---|------|---------|-------------|
| 1 | Agent bytecode | Already there (partly) | `run_plan` / `do_actions` run a batch of phone steps with no model call in between, resume where they stopped (FastActions.skipDone) and replay remembered plans (TapLoop.Plans). Loops / ifs / waits as a general language would let a model's guess run unchecked across steps that can send, pay or delete; not worth it without a verifier. |
| 2 | Speculative tools | **Built** (safe slice) | After web_search on Wi-Fi, the top 2 pages are prefetched into the tool cache. Speculating screen reads or memory is pointless (screens change, memory lookups are ms) and on metered data it would spend the user's bundle. |
| 3 | Draft-and-verify cascade | No | Two models per reply makes the common case slower and dearer; the gateway already picks a fast model per task. Worth it only if a measured quality gap appears on a task. |
| 4 | Optimistic answers | No | Offline Brain (0.5B) answers would often be wrong, then visibly swap. Flicker on a wrong answer costs more trust than the latency saves. |
| 5 | Screen diffs | Already there | readScreen sends "Same screen in X. Changed or new: …" (BlazeAccessibilityService), only the newest screen stays in history (FastActions.pruneScreens). |
| 6 | No streaming on tool turns | Already there (in effect) | Text written beside tool calls becomes a status step, call-like text never shows; streaming itself costs nothing measurable. |
| 7 | Prompt JIT | Mostly there | Skills (≤3 per turn), specialists, project brief (≤600 tokens), docs and tools are already retrieved per turn; the static part stays in the provider's cached prefix, which is the cheap part. Deleting the kernel rules would lose safety and honesty behaviour. The new token ledger will show if the system prompt is really the top cost. |
| 8 | Policy engine | Already there | ConfirmGate, ActionProof, ClaimGuard, PrivateChat, Modes.block are deterministic code gates. Prompt rules stay too: a gate stops a bad action, the rule stops the model trying. |
| 9 | Adaptive thinking budget | **Built** | ThinkBudget classifier replaces the length < 80 rule. |
| 10 | Simulated duo | No | Duo's value is two independent replies that can disagree; one forward pass playing both collapses into one voice agreeing with itself, the exact yes-man problem v0.32.0 fixed. |
| 11 | Parallel interpretations | No | 2–3× the cost on every ambiguous ask, and the user still has to pick. One short question is cheaper. |
| 12 | Calibrated confidence | Not now | Model-stated confidence is poorly calibrated on free models; ActionProof + RetryGuard check the claims that matter. |
| 13 | Blackboard memory | Already there (partly) | Long chats moves big tool results aside (LongChat.aside / read_artifact); Duo rooms share their tool steps. |
| 14 | Virtual-memory context | Already there | AutoCompact folds old turns into a summary, search_chat / read_turn fault them back in. |
| 15 | Trajectory distillation | Already there | TapLoop.Plans keeps the steps of phone tasks that ended well and replays them as one run_plan; IntentCache maps exact commands to tools with no model. |
| 16 | Learned tool router | **Built** | LearnedRouter adds groups on top of the regex lists. |
| 17 | Personal reward model | Not now | Needs several candidate replies per turn to rank; Blaze makes one. |
| 18 | Self-editing playbooks | No | Learning already writes skills from what worked; letting it rewrite its own core prompts is a prompt-injection path. |
| 19 | Skill evolution | Not now | Needs a golden set with automatic scoring first. |
| 20 | On-device eval harness | Not now | The JVM tests in CI are the harness; replaying real tasks on the phone at night spends battery and data and touches real apps. |
| 21 | Nightly LoRA on Offline Brain | No | MediaPipe LLM Inference can't train; a 0.5B fine-tune on a phone would take hours of battery and heat for little gain. |
| 22 | Constrained decoding | Not now | MediaPipe has no grammar mode, and Offline Brain has no tools, so there are no calls to constrain. |
| 23 | App world model | Not now | Plan memory covers repeat tasks; a full screen graph per app breaks on every app update. |
| 24 | Tree search with undo | No | Phone actions mostly can't be undone (a sent message, a toggled setting). |
| 25 | Visual screen memory | Not now | Text ScreenMemory answers "what was that address"; image embeddings need a bundled model and storage. |
| 26 | Dreaming | No | Precomputing tomorrow's answers spends tokens and data on asks that mostly never come. Morning brief already covers the predictable part. |
| 27 | Model bandit | Not now | The model is picked by the gateway, not the phone, and it only records success and speed, not quality. A bandit on success alone would drift to fast weak models. Revisit in the gateway once there's a quality signal (retries, thumbs). |
| 28 | Micro-agent market | Already there (partly) | Specialists and background agents; cost-based bidding adds nothing while every model is free tier. |
| 29 | Content-hash tool cache | **Built** | ToolCache. |
| 30 | Split inference | Not now | A 0.5B model fills slots worse than FastLane / IntentCache's rules; it would add latency, not remove it. |
| 31 | Earned autonomy credits | No | Standing OKs (Autonomy) are explicit and per category, money never gets one. Autonomy that grows on its own from "successes" the agent itself judges is the wrong trust model for sends and payments. |
| 32 | Proof-or-it-didn't-happen | Already there | ActionProof / ClaimGuard: a claimed action needs a tool from its group that succeeded this turn, or the claim is replaced with an honest line. |
| 33 | Token ledger per feature | **Built** (report only) | TokenLedger. Weekly self-optimisation is left out on purpose: it would change prompts with nobody reviewing. |
