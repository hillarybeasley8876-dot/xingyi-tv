# Brief: keep the user's words off shared storage

## Why
`BridgeLog` writes `bridge-log.txt` and `BridgeService.writeStatus` writes `status.txt` into
`getExternalFilesDir(null)` (= `/sdcard/Android/data/xyz.erapi0neer.xiaoaibridge/files`). On Android 9 other apps holding
storage permission can read that folder, and these files contain what the user said to XiaoAi and the model's answers.

## What to build
1. **`BridgeLog.java`**: keep the in-memory ring of the last 200 lines and the `Log.i` mirror, but the file goes to the app's
   PRIVATE storage: `new File(context.getFilesDir(), "bridge-log.txt")`. In the constructor, delete leftover shared copies:
   `bridge-log.txt`, `bridge-log.txt.tmp` and `status.txt` in `context.getExternalFilesDir(null)` (null-safe).
   Add `synchronized String all()` returning every buffered line (newline-separated).
2. **`BridgeService.java`**:
   - The `STATUS` control broadcast no longer writes a file. It builds the same status text as today and returns it to the
     caller through the ordered-broadcast result: `control.setResultCode(1)` and `control.setResultData(<base64 NO_WRAP of the UTF-8 text>)`.
     (`am broadcast` from adb is ordered and prints `Broadcast completed: result=1, data="..."`.) Pass the receiver into the method
     that answers, so `setResultData` is called on the receiver currently handling the broadcast, synchronously inside `onReceive`.
   - New control action `LOG` (`PKG + ".LOG"`, registered with the same DUMP permission): result data = base64 of `log.all()`.
   - Nothing in BridgeService writes under `getExternalFilesDir` any more (the one-time model import in `Llm.provision` is not yours; leave it).
3. **`bridge.sh`**: `status` and `log` call the broadcasts and decode the result instead of reading files:
   parse `data="..."` from the `am broadcast` output and decode it with `base64 -d`. Print a clear message if no data came back.
   Add a `log` usage line to the header comment. Keep every other command as it is.
4. **Test**: add `tests/StatusTextTest.java` only if you move pure logic into a pure class; otherwise no new test is required.

## Acceptance notes
`tests/check_private_logs.py` (lead-owned) checks the source: BridgeLog must use `getFilesDir()` for its file, BridgeService must not
write `status.txt`, both `STATUS` and `LOG` must use `setResultData`, and `bridge.sh` must not `cat` files under `/sdcard`.

## Do NOT change
`Takeover.java`, `Harness.java`, `Llm.java`, the song modules, existing tests, `AndroidManifest.xml`.
