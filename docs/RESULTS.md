# Results

All figures below are measured on the target device. "verified on device" means the
feature was exercised through the UI on the phone over `adb` and the output inspected.

## 1. Target device (read via `adb`)

| Property | Value |
|---|---|
| Model | realme narzo 60 5G — `ro.product.model = RMX3750` |
| SoC | `ro.soc.model = MT6833` (MediaTek Dimensity 700 / marketed "6020"), TSMC 7 nm |
| CPU | 2× Cortex‑A76 @ 2.2 GHz + 6× Cortex‑A55 @ 2.0 GHz |
| CPU features | `fp asimd aes pmull sha1 sha2 crc32 … asimddp` — **`asimddp` yes, `i8mm` no**, no SVE |
| RAM | `MemTotal` 7.82 GB; `MemAvailable` **~2.6–2.8 GB** in practice |
| Storage | 106 GB `/data`, ~41 GB free |
| OS | Android 15 (`ro.build.version.sdk = 35`) |
| ABI | `arm64-v8a` |

## 2. App size

| Build | APK | Notes |
|---|---|---|
| Debug | ~45 MB | unstripped native lib + Compose tooling + OkHttp debug symbols |
| **Release** (R8 + `shrinkResources` + `debugSymbolLevel = none`) | **15.5 MB** | `libvireo_llm.so` **5.9 MB** (whole llama.cpp + ggml, arm64), rest = minified DEX + Compose + OkHttp + PdfBox. **No model bundled** (`model_catalog.json` ≈ 1 KB). |

Verified on device: the 15.5 MB release APK does the full offline path under R8 —
in‑app model download → SHA‑256 verify → activate → offline generation
("12 times 8 is 96", 15.6 tok/s).

## 3. Inference performance

`Q4_0`, `n_ctx = 2048`, `n_threads = 2`, KV cache f16, `LLAMA_LOAD_MODE_NONE`, Android 15.

| Model | On disk | Load | Generation | Prompt‑eval |
|---|---:|---:|---:|---:|
| Qwen2.5‑0.5B‑Instruct | 0.41 GB | ~3.4 s | **13–15 tok/s** | 17–30 tok/s |
| Llama‑3.2‑1B‑Instruct | 0.74 GB | ~2.4 s | **5–7 tok/s** | 11–12 tok/s |

**Thread sweep** (Llama‑3.2‑1B) — why the default is 2:

| threads | gen tok/s | prompt tok/s |
|---:|---:|---:|
| **2** | **5.0–7.0** | **12.0** |
| 3 | 3.7 | 5.8 |
| 4 | 4.1 | 7.7 |
| 6 | 5.2 | 11.0 |

Adding the A55 "little" cores does not help — llama.cpp splits work evenly and then waits
on the slow cores.

**Diagnostics benchmark** (in‑app, 3× greedy 96‑token generations), Qwen2.5‑0.5B:
`gen 7.72 tok/s · prompt‑eval 11.36 tok/s · peak battery 36.6 °C · max thermal status none`
→ written to `bench.csv`.

**The optimisation that mattered:** with AGP's default CMake `Debug` native build, ggml runs
unoptimised with assertions on — measured **0.22 tok/s**. Forcing `CMAKE_BUILD_TYPE=Release`
gave the numbers above (~30–60×).

## 4. Thermal behaviour

The phone held **~36–38 °C** (battery) across every test session, including ~10 back‑to‑back
generations and the benchmark. Thermal status stayed `none` / `light`; `getThermalHeadroom`
read a real ~0.25–0.47 (not NaN) on this device.

| Governor tier | Trigger (tunable) | Effect — verified on device |
|---|---|---|
| **NORMAL** | status ≤ light, headroom < 0.75, battery < 40 °C | full speed (6.9 tok/s baseline on 1B) |
| **ECO** | status = moderate, or headroom ≥ 0.75, or battery ≥ 40 °C, or user "Eco Mode" | 30 ms/token sleep → **6.9 → 2.6 tok/s**; also lets the SoC governor park the big cores |
| **PAUSE** | status ≥ severe, or headroom ≥ 0.92, or battery ≥ 44 °C | generation **held** (spins, keeps the KV cache); the "❄ Cooling down" banner shows; switching back to a cooler tier **resumes to completion** (verified: a held turn resumed and finished 512 tokens) |

`ThermalPolicy.classify(...)` is a pure function with **8/8 unit tests**
(`app/src/test/java/com/vireo/thermal/ThermalPolicyTest.kt`).

## 5. Model manager

Verified on device (in both the debug and the R8 release build):

- catalog renders 5 models with size, quant, licence, `minRamMb` and a RAM warning;
- **download** Qwen2.5‑0.5B (409 MB) with a live progress bar + MB/s, resumable `.part`;
- the app's **SHA‑256** of the finished file matched the catalog value exactly
  (`7671c0c3…` for Qwen‑0.5B, `a0f7b4e1…` for EmbeddingGemma);
- **Activate** persists the choice — the app auto‑loads that model on the next launch;
- **Delete** removes the file and frees the space.

## 6. On‑device RAG

- **Embedder:** EmbeddingGemma‑300M Q8 in llama.cpp embedding mode, **768‑dim**, mean
  pooling, L2‑normalised. Loads in ~2 s and **coexists in RAM** with the chat model (no OOM
  on the 8 GB device — chat 0.4–0.8 GB + embedder ~0.35 GB).
- **TXT ingest:** an OS‑notes `.txt` → 2 chunks → asked *"What is Belady's anomaly"* →
  grounded answer (*"…for the FIFO page replacement algorithm, increasing the number of
  page frames can sometimes increase the number of page faults"*) with `[1]/[2]` sources
  citing the right file + page.
- **PDF ingest (in the release build):** a 1‑page networks PDF via PdfBox‑Android →
  2 chunks → asked *"Explain the TCP three‑way handshake"* / *"What does the Nagle algorithm
  do"* → correct grounded answers, `[1]/[2]` sources → `vireo_rag_test.pdf p.1`. This also
  confirms the PdfBox R8 keep‑rules.
- Citations are built in Kotlin from the retrieved chunks, so an `[n]` the model invents
  maps to nothing.

## 7. Per‑feature evidence

| Feature | Screenshot |
|---|---|
| M0 — Compose scaffold on the phone | `docs/images/00-scaffold.png` |
| M1 — first on‑device streamed answer (Llama‑3.2‑1B) | `docs/images/07-m1-inference.png` |
| Multi‑turn chat + tok/s + thermal chip | `docs/images/01-chat.png` |
| Model manager | `docs/images/02-models.png` |
| Notebook list | `docs/images/03-notebooks.png` |
| Notebook RAG — grounded answer + page citations | `docs/images/04-notebook-rag.png` |
| Diagnostics — device / models / thermal / benchmark / export | `docs/images/05-diagnostics.png` |
| Thermal PAUSE — "Cooling down" banner, reply held | `docs/images/06-thermal-pause.png` |
| No-model empty state | `docs/images/08-no-model.png` |

## 8. Known limitations

- On a Dimensity 700, **1B is the comfortable ceiling**; 1.5B works, 3B is RAM‑tight
  (full‑file read, no `mmap`) and slow (~3 tok/s) — the catalog and a RAM warning flag it.
- The 0.5B model answers fast but reasons poorly (it is the "quick lookup" option, not the
  recommended default).
- `mmap` is disabled globally because it hangs on this OEM build — so large models can't
  benefit from a smaller resident set.
- Downloads survive navigation within the app but **not a full process kill** (no
  foreground service yet); a `.part` file lets them resume on the next open.
- PDF ingest is capped at 60 pages; DOCX and web links are not supported (Stage‑II).
- The RAG retrieval runs on the raw question — no query‑rewrite / multi‑agent step yet.

## 9. Stage‑II backlog

Researcher→Tutor→Visualiser multi‑agent chain · chart rendering · DOCX / web‑link ingest ·
HNSW vector index · foreground download service · quantised KV cache tuning · desktop↔mobile
notebook sync.
