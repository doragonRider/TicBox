# Pinned scrcpy server with live streaming controls

The payload remains compatible with the scrcpy 4.1 framing used by this project. It adds two control messages: an empty one (`127`) to request a new sync frame from the active encoder, and one (`126`) carrying a 4-byte big-endian bit rate for it. The Agent advertises these extensions separately; callers must not send them to an unmodified server.

The source is Apache-2.0 licensed scrcpy v4.1, commit `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0`. `upstream-source.tar.gz` contains only 94 original entries: the production Java/AIDL files and LICENSE needed to compile the server. `upstream.json` commits every original file and the archive digest. The sources are unchanged in that archive; `request-keyframe.patch` is the complete local modification.

The Controller treats an uninitialized capture-control reference as a no-op; that reference is published with volatile visibility from the encoder thread. `CaptureControl.requestKeyFrame()` holds the same monitor used to register/unregister the current MediaCodec. It calls `setParameters(Bundle)` with `PARAMETER_KEY_REQUEST_SYNC_FRAME=0` only while a codec is registered and no reset is pending. Unregister completes before the encoder is stopped/released. Requests during initialization, reset, release, or an unsupported codec are harmless no-ops; the caller owns bounded retries and failure reporting. The operation does not signal EOS, stop capture, reset the encoder, replace sockets, or touch audio.

The original empty `RESET_VIDEO=17` and every other control type keep their framing. The host test exercises actual patched production classes with Android API doubles: active/inactive/rejected requests, reset ownership, concurrent unregister, and a sequence of extension/legacy/text messages. The doubles are never packaged.

## Optional constant bitrate for UDP

The additional internal option `video_prefer_cbr=true` is sent only for UDP sessions. After creating the actual encoder, the server checks that encoder's MIME-specific EncoderCapabilities for BITRATE_MODE_CBR. If declared supported, it sets MediaFormat.KEY_BITRATE_MODE before configure, retaining the requested bitrate, frame rate, codec and dimensions. A caller's explicit bitrate-mode option takes precedence. Missing/unsupported capabilities retain the original mode; ordinary reliable sessions do not run this probe. No encoder or transport fallback is introduced.

This keeps rate control from being left entirely to the device default. CBR can reduce quality in complex scenes and does not impose a hard per-frame byte cap or guarantee that vendor firmware obeys the target. Tests cover the actual policy with Android capability/format doubles, not hardware encoder conformance. It adds no diagnostic task and applies with frame statistics disabled too.

## Optional encoder readbacks

Only a UDP request with frame statistics enabled sets `video_diagnostics=true`. The encoder reports a bounded whitelist once after configuration and on at most two output-format changes per capture generation: actual codec name, SDK, hardware/CBR capability and separate configured/input/output bitrate mode, bitrate, frame rate and latency. B-frame/reorder/operating-rate fields are included only if the codec reports them. Missing fields remain unknown. Input/output formats are codec readbacks, not measurements or proof that hardware obeyed the requested bitrate.

Metadata uses a dedicated prefix on the existing console error descriptor. The implementation deliberately uses the console held by Ln, because the server disables System.out/System.err. The host test disables System streams and verifies a real child-process stderr record. The Agent's existing stderr drainer admits at most 2048 bytes of JSON per line, drops oversized/incomplete or unrelated log lines, sanitizes the fields and enqueues them in the existing asynchronous diagnostics publisher. There is no raw log forwarding, new reader/timer thread, media wire change or periodic codec query. With statistics off there is no metadata collection or parsing.

The format snapshots also carry a bounded nested capability object: the declared bitrate range, actual capture dimensions, size/rate support and frame-rate range for that size. The phone logs this separately so the global detail budget cannot truncate format observations. These are codec declarations, not measured throughput or a verdict that an otherwise working configuration must be changed.

With diagnostics enabled, source output accounting runs in the existing encoder loop and publishes at most once per two seconds of observed output. It separates time waiting for a codec output buffer from time writing that buffer to the local Agent socket. Encoded bytes over increasing media PTS intervals provide a media-timeline bitrate estimate; this is distinct from wall-clock delivery already limited by the sender. Duplicate/backwards PTS suppress that estimate, and idle media gaps remain included. No encoded bytes/images are retained. With statistics disabled there is no accounting object, per-frame clock measurement, timer or output report. These readbacks add no codec switch or transport fallback.

## Live bit rate

Control message `126` carries the encoder's new target in bits per second, at least 1. `CaptureControl` keeps the latest value and, under the same monitor as the keyframe request, applies it with `setParameters(Bundle)` and `PARAMETER_KEY_VIDEO_BITRATE` while a codec is registered and no reset is pending. A codec registered later, after a reset or a new capture, gets the kept value too, so the target survives encoder restarts. A codec that refuses the parameter is ignored, as with the keyframe request. The server never chooses a rate itself: the Agent lowers it while pictures queue up on its side of the UDP sender and raises it again once they stop. `VideoBitRateTest` covers the framing and the encoder side with the same Android doubles.

## Pacing the encoder's input

With `video_tail_repeat_us` above 0 (UDP only, one frame interval), the screen capture draws through OpenGL, with an identity `AffineOpenGLFilter` when there is no transform, and `OpenGLRunner` decides when each picture goes into the encoder. The capture reports this through `SurfaceCapture.pacesFrames()`, and the encoder is then configured without `max-fps-to-encoder`. If OpenGL can't start, the capture takes the direct path and requests one restart, configured with the framework's limit.

- **Frame rate.** `PresentationGate` keeps pictures to `max_fps`. The framework's limit drops a picture that comes too soon after the last input, and a picture just after a copy (below) was lost that way. Here an early picture waits for its turn, at most one interval and with the framework's 2 ms tolerance. Copies count against the rate too, so a picture right after a copy waits for the next slot and the encoder never gets two inputs within one interval. The SurfaceTexture keeps only the newest picture, so one that waits is replaced, never queued.
- **Copies of the last picture.** Some encoders, MediaTek's among them, give a picture back only after later inputs arrive, whatever `KEY_LATENCY` says, and the framework's `repeat-previous-frame-after` repeats a picture only once. When the screen stops changing, the last real change would never leave the device. When no new picture has come 1.5 intervals after the last, `OpenGLRunner` draws copies of it, one per interval, until the encoder holds no picture (nor a sync frame's copy) any more, 12 at most. An encoder that gives every picture back at once gets none. A copy's timestamp is the current time and at least 1 ms after the one before.
- **Backlog.** `EncoderInputLog` records each input's timestamp and the time just before it is swapped into the encoder's surface, 256 entries at most. The encoder loop takes each output from it once the output has been written to the Agent, so a blocked write keeps counting. With at least 6 inputs in the encoder, the oldest there for 100 ms or more, and an output in the last 100 ms, the encoder is backed up: the next picture waits until an output ends the backlog, 200 ms at most, and copies are put off without using any up. An idle encoder holding its last few inputs is not backed up.
- **Sync frames.** An encoder makes a requested sync frame from its next input, which a still screen never sends. After a successful request `CaptureControl` asks the capture for an input: `OpenGLRunner` draws a copy of the current picture at once, and copies after it until that one is out.

With `video_diagnostics=true`, the encoder loop's matches also feed a second record, `input_sample`, in each two-second window of output accounting. It reports the time from input to output for pictures and copies, and how many later inputs were already in the encoder when each picture came out. It also counts inputs that never came out and outputs that were never drawn here, such as the framework's own repeat. Without OpenGL there is nothing to match, and no record. `PacedInputTest` covers the gate and the backlog with the production classes.

Also with diagnostics, on Android 12 and later, each configured encoder reports `vendor_parameters`: the names of its vendor parameters and their value types from `getParameterDescriptor`, never their values. The names lose the dotted prefix they all share and rate control names come first, so they fit the 2048-byte line; `omitted` counts the names that didn't.

## Offline reproduction

Required tools are Node 26+, a JDK supporting Java release 8, Android SDK platform `android-37.2`, build-tools `37.0.0`, `tar`, and `patch`. `JAVA_HOME` and `ANDROID_HOME` select the installed tools. No package download or target execution occurs.

From the repository root:

```sh
node tools/build-scrcpy-server.mjs --check
```

This verifies the original source inventory, applies the patch without fuzz, compiles all production Java and generated AIDL, runs D8, verifies every compiled class is in DEX, runs host lifecycle/framing and rate-control tests, and compares a deterministic ZIP byte for byte with the shipped payload. `server-build.json` records tool input hashes, patch/source hashes, and the expected artifact digest. Both Android backend check tasks include this verification. Each external build/test phase has a 120-second deadline; concurrency fixtures release and join their workers even when an assertion fails.

The JAR includes only production `classes.dex`, the Apache license, and build identity. It does not include an Android application manifest/resources or unused AGP-added Kotlin runtime assets: the server's upstream production sources have no Kotlin dependency. Source line information and all production classes are retained. The payload is loaded via `app_process`, not installed as an Android package.

When intentionally changing the patch or reviewed build tool inputs:

```sh
node tools/build-scrcpy-server.mjs --write
```

Review the new payload digest, then update `AgentScreen.SERVER_SHA256`, `SCRCPY-NOTICE.txt`, and the backend documentation before checks. Verification never rewrites the shipped JAR. Real codec support for dynamic keyframes still requires device testing; an accepted request is not a guarantee that a keyframe has already reached the receiver.
