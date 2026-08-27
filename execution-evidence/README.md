# Real-Device Execution Evidence

This directory is a frozen, public evidence snapshot for the final real-device demo run. It correlates Android logs and device-side artifacts with the matching Google Cloud activity records. The Google account email and reusable credentials are not included. Project, device, media-path, prompt, response, request, trace, and internal-path fields are intentionally retained for auditability.

No video, audio, or other family-media binary is stored here. Cloud Logging represents each uploaded video window only as a `video/mp4<sha512...>` fingerprint.

## Run identity

| Field | Value |
| --- | --- |
| Device run | `run-4af3fb61-7b61-49e0-becd-84f0de0480a3` |
| Android application | `com.chill.familyvlog` |
| Wireless ADB device | `192.168.3.78:46245` · `2412DPC0AG` · `rodin_global` |
| Device timezone | `Asia/Ho_Chi_Minh` (`+07:00`) |
| Google Cloud project | `familyvlog-a8a84` |
| Managed model | `gemini-3.6-flash` in `global` |
| Cloud activity interval | `2026-08-26T22:58:39.955685Z`–`2026-08-26T23:01:51.048492Z` |
| Evidence capture | `2026-08-27T19:28Z`–`19:30Z` |

The device artifacts are the newest completed run directory currently present on the phone. The Cloud interval is the last contiguous seven-trace `gemini-3.6-flash` activity group and aligns with the device artifact write time. Cloud Logging does not contain the device run UUID, so this cross-source match is a timestamp-and-content correlation rather than a shared-ID join.

## Direct answers

| Question | Evidence-backed result |
| --- | --- |
| What was uploaded? | **4** original clips totaling **46.796666 s**. Device preparation produced **6** uploaded analysis windows totaling **50.529999 s** because adjacent windows overlap by **3.733333 s**. |
| What did the AI identify and keep? | **11** events were identified; the story planner selected **5** events. |
| How long was the edit? | Planned clip sum: **20.866667 s**. MediaStore-reported output duration: **20.895 s**. |
| How long did the task take? | **220.339913948 s** (**3 min 40.3 s**) from the likely Create Vlog touch to the original output file mtime. The touch is logged against `MainActivity`, but Android does not record the control name. |
| How long did Gemini take? | First request to final plan choice: **191.092807 s** wall time. The sum of the seven observed request-to-choice spans is **75.535327 s**. The latter is the **75.5 s AI-stage** headline metric. |
| How long did local original editing take after the plan? | **6.201421948 s** from the final Cloud plan choice to the original output file mtime, or **6.088000001 s** from the two device JSON files being written to that output mtime. |
| How long did subtitles take? | The directly observable original-output to subtitled-output interval is **17.096000001 s**. A time-correlated mediaserver signal appears **10.244913949 s** before the subtitle output, but the app has no success-path subtitle-start log, so that shorter interval is an inference rather than an exact app-timed stage. |
| What does the README's 23.2 s local metric represent? | Device JSON write to optional subtitled-output mtime is **23.184000002 s**, rounded to **23.2 s**. It includes the original edit and optional subtitle copy and ends later than the 3 min 40.3 s original-output measurement. |

Cloud request-to-choice spans are observable service intervals, not a claim about pure accelerator compute time. The **115.557480 s** difference between the 191.092807 s Cloud wall span and the 75.535327 s call-span sum covers phone-side preparation between calls, segment handling, file reads, request construction, and network gaps.

## What the two device JSON files show

[`device/video_understanding.json`](device/video_understanding.json) contains the 11 events returned by the six video-understanding calls. [`device/edit_plan.json`](device/edit_plan.json) contains the five events restored into the executable local plan after the seventh, JSON-only story-planning call.

### Retained in the final plan

| Order | Event | Source range | Role | Retained content |
| ---: | --- | ---: | --- | --- |
| 1 | `video_01_s01_e01` | `0.000–2.000 s` | opening | Brother holding a toy shovel by the shore, with wave sound, establishes the beach scene. |
| 2 | `video_01_s01_e02` | `2.000–8.000 s` | development | Brother digs sand and shallow water, then stands up. |
| 3 | `video_02_s01_e02` | `4.000–11.000 s` | development | Mother-and-child sandpit conversation followed by his smile. |
| 4 | `video_02_s02_e01` | `10.266666–13.133333 s` | highlight | Brother laughs toward the camera while standing in the sandpit. |
| 5 | `video_03_s01_e01` | `0.000–3.000 s` | ending | Brother and sister walk together carrying a bucket and shovel. |

### Not selected by the planner

| Event | Source range | Omitted content |
| --- | ---: | --- |
| `video_02_s01_e01` | `0.000–4.000 s` | Earlier sandpit setup and the mother's first question. |
| `video_03_s01_e02` | `3.000–6.000 s` | The siblings look upward while the sister calls out. |
| `video_03_s01_e03` | `6.000–8.965 s` | Continued upward-looking and pointing with the shovel. |
| `video_04_s01_e01` | `0.000–7.000 s` | Opening of the potty scene and family dialogue. |
| `video_04_s01_e02` | `7.000–13.000 s` | Nose-covering action and laughter in the potty scene. |
| `video_04_s02_e01` | `11.200–16.200 s` | Continued face-touching in the potty scene. |

At the executable source-range level, the plan omits `video_01` after `8.000 s`, `video_02` before `4.000 s`, `video_03` after `3.000 s`, and all of `video_04`. That is **26.663333 s** of unique source timeline omitted. The two retained `video_02` ranges overlap by **0.733334 s** and are rendered as two sequential clips, so the planned clip sum is **20.866667 s** while the unique retained source timeline is **20.133333 s**.

## Evidence files

| File | What it proves |
| --- | --- |
| [`run-timeline.json`](run-timeline.json) | Machine-readable milestones, derived intervals, content counts, and explicit evidence-quality labels. |
| [`google-cloud/activity-log.sanitized.json`](google-cloud/activity-log.sanitized.json) | All **21** whitelisted activity entries: seven system messages, seven real user requests, and seven real model choices, including full prompts and responses. |
| [`google-cloud/call-summary.json`](google-cloud/call-summary.json) | Seven trace/span pairs, call stage, timestamps, media fingerprints, finish reasons, and timing totals. |
| [`device/logcat-app-uid.txt`](device/logcat-app-uid.txt) | Unmodified **41-line** Android `logcat` export for application UID `10430` in the run window. |
| [`device/logcat-correlated-excerpt.txt`](device/logcat-correlated-excerpt.txt) | Exact lines filtered from the full-device log for Activity creation, Photo Picker return, the next app interaction, and time-correlated media-system signals. This is an excerpt, not a complete raw log. |
| [`device/run-files-stat.txt`](device/run-files-stat.txt) | Direct phone-side nanosecond mtimes, sizes, and private paths for the final understanding and plan JSON files. |
| [`device/video_understanding.json`](device/video_understanding.json) | Four sources, 46.796666 s of source footage, and 11 actual model-described events. |
| [`device/edit_plan.json`](device/edit_plan.json) | Five selected clips, exact source ranges, roles, and model selection reasons. |
| [`device/original-output-mediastore.txt`](device/original-output-mediastore.txt) and [`device/original-output-stat.txt`](device/original-output-stat.txt) | Original output filename, path, MIME, duration, size, committed MediaStore state, and file timestamps. |
| [`device/subtitled-output-mediastore.txt`](device/subtitled-output-mediastore.txt) and [`device/subtitled-output-stat.txt`](device/subtitled-output-stat.txt) | Equivalent evidence for the optional subtitled copy. |
| [`SHA256SUMS`](SHA256SUMS) | Integrity hashes for every evidence payload except the checksum file itself. |

Verify the snapshot from this directory with `sha256sum -c SHA256SUMS`.

The full-device `logcat` window contained 1,782 lines of unrelated OS and other-process noise. It was retained only in a local temporary capture and is intentionally not published. The public app-UID log and correlated excerpt are direct subsets of that current device-buffer export, not reconstructed prose.

## Content-bearing Cloud records

The Cloud file is not a metadata-only aggregate. It preserves:

- two distinct system prompts used across seven calls;
- seven distinct user requests;
- seven distinct model JSON responses;
- six distinct `video/mp4<sha512...>` input fingerprints; and
- the final planning request containing the full merged understanding JSON and its five-event model response.

All seven `finish_reason` values are `stop`. A fingerprint proves which bytes the monitored request represented without placing those video bytes in this repository.

## Redaction and safety boundary

The public export was constructed with a Cloud Logging field whitelist. A scan over every published key and value found no Google account email, other email address, Google API key, OAuth token, JWT, `Authorization` or `Bearer` value, Cookie, PEM private key, service-account identity, or client secret. The active CLI account output and local authentication files were never exported.

As authorized for this public hackathon repository, the following remain visible: project and device identifiers; media paths, URIs, and filenames; prompts and model responses; family-video descriptions; request, trace, span, and insert identifiers; and app-internal storage paths. Actual media binaries remain excluded.

## Capture commands

The Cloud snapshot was exported without changing the active `gcloud` project:

```bash
EVIDENCE_GCP_PROJECT="familyvlog-a8a84"
EVIDENCE_ACTIVITY_LOG="projects/${EVIDENCE_GCP_PROJECT}/logs/firebasevertexai.googleapis.com%2Factivity"

gcloud logging read \
  "logName=\"${EVIDENCE_ACTIVITY_LOG}\" AND timestamp>=\"2026-08-26T22:58:30Z\" AND timestamp<=\"2026-08-26T23:02:00Z\"" \
  --project="${EVIDENCE_GCP_PROJECT}" \
  --order=asc \
  --limit=100 \
  --format=json \
| jq 'map({
    timestamp,
    receiveTimestamp,
    severity,
    insertId,
    logName,
    resource: {type: .resource.type, labels: .resource.labels},
    labels,
    jsonPayload,
    trace,
    spanId,
    traceSampled
  })'
```

The Android log and private JSON artifacts were read without starting, clearing, reinstalling, or uninstalling the app:

```bash
EVIDENCE_ADB_SERIAL="192.168.3.78:46245"
EVIDENCE_RUN_ID="run-4af3fb61-7b61-49e0-becd-84f0de0480a3"

adb -s "${EVIDENCE_ADB_SERIAL}" logcat -b all -d -v epoch --uid=10430 -T 1787784990.000 \
| awk '$1 >= 1787784993.499 && $1 <= 1787785344.111'

adb -s "${EVIDENCE_ADB_SERIAL}" exec-out run-as com.chill.familyvlog \
  cat "no_backup/vlog-runs/${EVIDENCE_RUN_ID}/video_understanding.json"

adb -s "${EVIDENCE_ADB_SERIAL}" exec-out run-as com.chill.familyvlog \
  cat "no_backup/vlog-runs/${EVIDENCE_RUN_ID}/edit_plan.json"
```

Android's ring buffer and the Cloud Logging retention window are finite. This directory is the durable, checksummed snapshot used for repository review.
