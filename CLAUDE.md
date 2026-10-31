# OneAssist

Personal Android finance helper. Read ARCHITECTURE.md before changing anything.

- Team agents live in `.claude/agents/`: architect, backend, frontend, security.
- Pure logic goes in `core` with unit tests. Test it with `gradle -p core test` (works without the Android SDK).
- The Android SDK is not available in Claude's cloud container; the full APK is built by GitHub Actions.
- Amounts are Long cents. No secrets in the repo. Anything touching storage, network, permissions or the assistant gets a `security` review.
