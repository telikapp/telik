# Implementation Plan: CI release packaging

**Spec**: [spec.md](spec.md)

1. Add `--stacktrace` to the Gradle step of `.github/workflows/build.yml` and `release.yml`. Then the pull request run shows the cause.
2. Read the cause in the pull request run. Compare it with the runner differences in `spec.md`.
3. Fix the cause in the build or the workflow. Result: the Gradle heap goes from 2 GB to 4 GB. Keep the pins of the constitution (principle IV).
4. Record the cause and the fix in `spec.md`.
