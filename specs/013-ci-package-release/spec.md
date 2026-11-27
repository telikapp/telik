# Feature Specification: CI release packaging

**Feature Branch**: `fix/ci-package-release`
**Created**: 2026-11-27
**Status**: Fixed in pull request #39. The `android` job must pass on `main` after the merge (SC-001).
**Input**: CI run 46002236540 on `main` (commit 509300a, a docs-only merge) failed two times on `:app:packageRelease`
with `A failure occurred while executing com.android.build.gradle.tasks.PackageAndroidArtifact$IncrementalSplitterRunnable`.
The log has no cause.

## Findings

- The code of 509300a is the same as the code of 927d2f0. The diff touches only `specs/`. Run 45617049936 built 927d2f0 with no error.
- The runner changed between the two runs:

  | | Run 45617049936 (pass) | Run 46002236540 (fail) |
  |---|---|---|
  | Runner image | ubuntu-24.04 20261116.133.1 | ubuntu-24.04 20261122.23.1 |
  | JDK from the tool cache | Temurin 21.0.13+9 | Temurin 21.0.13.1+2 |
  | `setup-java` Gradle cache | not found | restored, 660 MB, saved by run 45617049936 |

- A local build of 509300a on macOS arm64 passes with Temurin 21.0.12+8 and with Temurin 21.0.13.1+2. The APK is 26.8 MB.
- The CI step does not use `--stacktrace`, so Gradle prints only the top exception.

## Cause

The Gradle daemon ran out of heap (`-Xmx2048m` in `gradle.properties`). With `--stacktrace`, run 46010693143 shows:

```
Caused by: java.lang.OutOfMemoryError: Java heap space
    at com.android.zipflinger.NoCopyByteArrayOutputStream.<init>(NoCopyByteArrayOutputStream.java:27)
    at com.android.zipflinger.Compressor.deflate(Compressor.java:35)
    ...
    at com.android.builder.internal.packaging.ApkFlinger$writeFile$1.call(ApkFlinger.kt:194)
```

`packageRelease` compresses each native library in memory. Spec 012 added `libcloudflared.so` with legacy (compressed)
packaging. The CI step also runs the unit tests and lint in the same daemon. The heap was near its limit, so the result
changed from run to run:

| Tree | Gradle heap | Runs | Result |
|---|---|---|---|
| `main` 509300a | 2 GB | 46002236540 attempts 1 to 3, 46010693143 (inside the pull request) | 4 failures |
| Pull request merge (509300a + spec 013 files) | 2 GB | 46005714762, 46006767316, 46009598461 | 3 passes |
| `main` 509300a | 4 GB | 46011743903 (inside the pull request) | pass |

The runner region, the CPU (AMD EPYC 9V45), the free memory (14 GB), the disk (80 GB free), the branch state of the
checkout and the Gradle cache entry were the same in passes and failures. The cause is in the build settings, not in the
runner or the secrets.

## Fix

`org.gradle.jvmargs` is `-Xmx4g -XX:MaxMetaspaceSize=1g`. The hosted runner has 16 GB. `--stacktrace` stays in the CI
Gradle steps.

## Requirements

- **FR-001**: The CI Gradle step prints the full stack trace when a task fails.
- **FR-002**: `Build` on `main` passes again, with the same APK contents as before the failure.
- **FR-003**: The fix does not hide the cause. If the cause is outside the code (runner, secrets), the spec records it and what the owner must do.

## Success Criteria

- **SC-001**: The `android` job of `Build` passes on the pull request and on `main` after the merge.
