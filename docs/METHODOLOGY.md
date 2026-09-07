# Methodology

How Vireo was designed and built, and *why* each major choice was made.

---

## 1. Problem statement

Cloud LLM assistants need connectivity, send private study material to third‑party servers,
and cost money per query. The goal of this project is a study assistant that:

1. runs a useful language model **fully on a mid‑range Android phone**,
2. works **with the network switched off**,
3. does **not overheat** the phone or trigger hard thermal throttling,
4. ships as a **small app** — the model is *not* inside the APK; the user downloads it,
5. can answer questions **grounded in the user's own documents** with real citations.

Target device: **realme narzo 60 5G** — MediaTek MT6833 (Dimensity 700), 2× Cortex‑A76 +
6× Cortex‑A55, Mali‑G57, **8 GB RAM (~2.7 GB free in practice)**, Android 15, arm64‑v8a.
CPU features: `asimddp` (dot‑product) present, **no `i8mm`**, no SVE.

## 2. Objectives (Stage‑I)

| # | Objective | Result |
|---|---|---|
| O1 | Compile and run an LLM on the phone via a native runtime | llama.cpp built with the NDK, streamed inference |
| O2 | A usable chat UI — multi‑turn, streamed, persistent | done |
| O3 | Keep the device thermally safe during inference | thermal governor: eco‑throttle → pause → auto‑resume |
| O4 | Let users install models from inside the app; keep the APK tiny | 15.5 MB APK, in‑app catalog + resumable hash‑verified downloader |
| O5 | Basic Retrieval‑Augmented Generation over user files with citations | PDF/TXT/MD → embed → cosine search → grounded answer |
| O6 | Do all of the above **without Android Studio** | headless Gradle + NDK toolchain, driven from a terminal |

## 3. Key design decisions

### 3.1 Native Kotlin + llama.cpp via JNI — *not* a framework wrapper

| Option considered | Why rejected / chosen |
|---|---|
| **Native Kotlin + Compose + llama.cpp (git submodule) + ~250‑line custom JNI** | **Chosen.** Smallest APK; full control of the per‑token loop (needed for O3); one runtime for both chat *and* embeddings. |
| Fork an existing app (e.g. SmolChat) | Used only as a *reading reference* for the JNI lifecycle; forking would entangle originality and force someone else's module layout. |
| React Native + `llama.rn` | Base APK 20–40 MB, Node toolchain, and the per‑token thermal hook is awkward. |
| Google AI Edge / MediaPipe `LLM Inference` | No NDK needed, but `.task` bundles are large, the model choice is narrow, and you lose the token‑level pause hook. |

### 3.2 Quantisation and threading tuned to the SoC

- **`Q4_0` GGUF** — llama.cpp repacks it at load time into the ARM `dotprod` kernel path,
  which is exactly the feature this chip has (`REPACK = 1` at runtime confirms it).
- **2 threads.** A benchmark sweep on Llama‑3.2‑1B (see `docs/RESULTS.md §3`) showed
  2 threads ≈ best and coolest; adding the six A55 "little" cores *slows* generation
  (llama.cpp splits work evenly, then waits on the slow cores).
- **Full file read, not `mmap`.** `LLAMA_LOAD_MODE_MMAP` hangs indefinitely on this
  ColorOS / Android 15 build — for both FUSE `/sdcard` *and* internal storage (tested in
  M1 and again in M4). `LLAMA_LOAD_MODE_NONE` (read the whole file) is reliable.
- **Prompt decoded in `n_batch`‑sized chunks.** A single `llama_decode()` with
  `n_tokens > n_batch` trips a `GGML_ASSERT` → `abort()` → `SIGABRT`. Short single‑turn
  prompts never exceeded 128 tokens; the first real multi‑turn prompt did. Fixed by
  chunking (this bug was latent from M1 and only surfaced in M4 testing).

### 3.3 Thermal strategy

"Zero heat" is not physically achievable — sustained transformer inference on a 7 nm
mid‑range SoC *will* warm the phone. The achievable goal is **never hot to the touch,
never a hard OS throttle, auto‑pause under pressure**. The governor:

- reads `PowerManager` thermal **status** (a listener) + `getThermalHeadroom(10)` (a 0..1
  forecast, polled every 2 s — returns a real ~0.3 on this device) + **battery °C** from
  the sticky `ACTION_BATTERY_CHANGED` broadcast;
- boils them into a pure, unit‑tested function → `NORMAL | ECO | PAUSE`;
- acts **inside the per‑token JNI callback** (`ThermalPacer`): ECO inserts a 30 ms sleep
  per token (which also lets the SoC governor park the big cores → less heat); PAUSE spins
  until the tier clears, keeping the model's KV cache so generation resumes seamlessly;
- writes a telemetry CSV row every 5 s during generation for the evaluation graphs.

### 3.4 Model manager — the APK carries no model

`assets/model_catalog.json` (≈1 KB) lists each model's `id`, byte size, **SHA‑256**,
download URL, `minRamMb` and licence. The downloader (OkHttp) streams to a `.part` file,
supports HTTP `Range` resume, verifies the SHA‑256, then atomically renames it. A mismatch
deletes the partial file. Downloads run on a **process‑scoped** coroutine so they continue
while the user navigates elsewhere in the app.

### 3.5 RAG — citations from retrieval, not from the model

The abstract requires *authoritative* citations. So the pipeline:

1. extracts page‑numbered text (PdfBox‑Android for PDF; raw read for TXT/MD),
2. chunks it sentence‑aware (~900 chars, ~140 overlap),
3. embeds each chunk with **EmbeddingGemma‑300M** run through the *same* llama.cpp runtime
   in embedding mode (mean pooling, L2‑normalised — one native library, no ONNX),
4. stores vectors in memory and does a brute‑force cosine search (a phone notebook holds a
   few thousand chunks at most; this is <5 ms),
5. builds a **numbered** context block and a strict "answer only from the context, cite
   `[n]`" system prompt,
6. **builds the citation list in Kotlin from the retrieved chunks** (`fileName`, `page`).
   If the model writes `[7]` and there is no chunk 7, it maps to nothing.

## 4. Development approach

Milestone‑driven, each milestone ending with an **on‑device verification** before moving
on. Every build was installed on the real phone over `adb` and driven/screenshotted from a
terminal; there was no emulator and no Android Studio.

| Milestone | Deliverable | Verified on device |
|---|---|---|
| M0 | Toolchain + blank Compose APK | app launches |
| M1 | llama.cpp compiles + streams tokens | Qwen‑0.5B 13 tok/s, Llama‑1B ~6 tok/s |
| M2 | Multi‑turn chat UI, streamed, persisted | context recall works |
| M3 | Thermal governor | NORMAL / ECO / PAUSE + auto‑resume, 8/8 unit tests |
| M4 | In‑app model manager | downloaded a model, SHA‑256 matched, activated |
| M5 | On‑device RAG | grounded answer with page‑level citations |
| M6 | Release build + Diagnostics + polish | 15.5 MB APK, full offline path works under R8 |

## 5. Evaluation approach

- **Latency** — the native layer reports prompt‑eval and generation tok/s per turn; the
  Diagnostics screen runs a fixed 3× greedy 96‑token benchmark and writes `bench.csv`.
- **Thermals** — battery °C, thermal status, headroom and the governor tier are logged to
  `run.csv` every 5 s during generation; both CSVs are exportable from the app.
- **RAG quality** — checked that the top retrieved chunk matches the question, the answer
  is grounded in it, an out‑of‑scope question yields "I couldn't find that in your notes",
  and the citations point at the correct file and page.
- **App size** — the release APK is measured after R8 + resource shrink + native strip.

Numbers are in [`docs/RESULTS.md`](RESULTS.md).

## 6. Bugs found & fixed (kept here because they shaped the design)

| Symptom | Cause | Fix |
|---|---|---|
| `0.22 tok/s` on first run | AGP builds the native side as CMake `Debug` (no `-O2`, asserts on) | `CMakeLists.txt` forces `CMAKE_BUILD_TYPE=Release` — ~30–60× faster |
| Model load never returns | `mmap` over FUSE `/sdcard` (and later internal storage) hangs on this build | `LLAMA_LOAD_MODE_NONE` |
| `SIGABRT` in `llama_decode` on the first long prompt | one `llama_decode` with `n_tokens > n_batch` trips `GGML_ASSERT` | decode the prompt in `n_batch`‑sized chunks; `n_batch` 128 → 512 |
| Chat replies "invisible" | the OEM keyboard pans the whole window up (edge‑to‑edge, no `adjustResize`) — generation was fine all along | `android:windowSoftInputMode="adjustResize"` + `Modifier.imePadding()` |
| Spurious "Unresolved reference" build failures | Kotlin incremental‑compilation cache going stale after signature changes | `kotlin.incremental=false` (this module recompiles in seconds) |

## 7. Deferred to Stage‑II

Multi‑agent orchestration beyond a Researcher→Tutor chain, a visualisation agent + charts,
DOCX / web‑link ingest, an HNSW vector index, a foreground service so downloads survive a
full process kill, and desktop↔mobile notebook sync.
