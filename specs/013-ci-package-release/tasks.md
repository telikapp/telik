# Tasks: CI release packaging

- [x] T001 Add `--stacktrace` to the Gradle steps of `build.yml` and `release.yml`.
- [x] T002 Get the cause of the `packageRelease` failure from the pull request run. Run 46010693143 built the `main` tree with `--stacktrace`: `OutOfMemoryError: Java heap space` in zipflinger.
- [x] T003 Fix the cause. `org.gradle.jvmargs=-Xmx4g`. Run 46011743903 built the `main` tree with the new heap and passed.
- [x] T004 Record the cause and the fix in `spec.md`. Set the status.
- [x] T006 Remove the temporary diagnostic steps from `build.yml`.
- [x] T005 The `android` job passes on the pull request with the final workflow. Run 46013168047.
- [ ] T007 The `android` job passes on `main` after the merge.
