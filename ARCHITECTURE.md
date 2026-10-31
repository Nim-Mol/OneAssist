# OneAssist architecture

A personal Android finance helper for one person in the Netherlands (ING NL, Revolut, a credit card).
Local-first, no login, no server, sideloaded APK. Owner: the `architect` agent.

## Goals (from the owner)
1. Find money leaks.
2. Import and categorize transactions with as little manual work as possible.
3. Cover ING, Revolut and the credit card.
4. Overview of subscriptions.
5. Savings suggestions.
6. A personal assistant that knows this person's money, not generic advice.

## Modules

```
core/   Pure Kotlin (JVM). Models, parsers, rules, detectors, prompt builder. No Android imports.
        Fully unit tested. Builds alone with `gradle -p core test` (no Android SDK needed).
app/    Android app (Kotlin, Jetpack Compose). Depends on core.
  data/       Room database encrypted with SQLCipher, DAOs, repositories (FinanceRepository).
  ingest/     BankNotificationListener, CSV share import, screenshot OCR (ML Kit, on device).
  assistant/  AnthropicClient (OkHttp) + ApiKeyStore (Android Keystore).
  security/   DatabaseKeyProvider, AppLock (BiometricPrompt).
  ui/         Compose screens + ViewModels. Talks only to repositories.
```

Dependency injection is manual: `OneAssistApp` creates one `AppContainer` holding the database,
repositories, parsers and clients. ViewModels get what they need from it.

## Data flow

```
Bank notification ─┐
CSV share ─────────┼─> core parser ─> RawTransaction ─> de-dup ─> Categorizer ─> Room (encrypted)
Screenshot (OCR) ──┘                                                              │
                                                                                  v
                             SubscriptionDetector, LeakDetector, SavingsAdvisor (core, on demand)
                                                                                  │
                                         UI screens <──────────────────────────────┤
                                                                                  v
                     AssistantPromptBuilder (aggregates only) ─> Claude Haiku API ─> chat answer
```

### Import sources (priority order)
1. **Notifications** (automatic). `BankNotificationListener` accepts only the ING and Revolut
   package names, parses amount and merchant, and stores a transaction. Raw notification text is not stored.
2. **CSV share** (monthly, fills gaps such as direct debits). ING NL exports are semicolon separated
   with Dutch number format; Revolut exports are comma separated. Parsers detect the bank by header.
3. **Screenshot** (credit card or anything missed). ML Kit text recognition runs on device; a core
   parser turns OCR lines into transactions.

### De-duplication
A transaction from a CSV or screenshot matches an existing one when the account and amount are equal,
the dates are within 3 days, and the normalized merchant names match. CSV wins (it is the bank's
own record), so a matched notification row is replaced by the CSV row.

### Money
Amounts are `Long` cents. Negative is money out. Display format is Dutch: `€ 1.234,56`.

### Categorization
Rules (`CategoryRule`) match on normalized merchant or description, highest priority first. Ships with a
Dutch starter set (Albert Heijn, Jumbo, NS, Ziggo, KPN, Thuisbezorgd, ...). When the user changes a
category in the UI, a learned rule is saved with higher priority than the defaults.

## Assistant (optional)
- Model: Claude Haiku via the Anthropic Messages API. The user pastes their own API key in Settings.
- The prompt contains only aggregates: monthly totals per category, top merchants by spend,
  subscriptions, detected leaks. No IBANs, account numbers, people's names or raw descriptions.
- Read-only by design: the assistant has no tools and cannot act. Merchant names are wrapped as
  data, so prompt injection through them cannot trigger anything.
- Cost target: a few cents per conversation.

## Security baseline (owner: `security` agent)
- Database encrypted with SQLCipher; the key is random, wrapped by an Android Keystore key.
- Biometric / device credential lock on launch and after 60 s in the background. FLAG_SECURE on.
- `allowBackup=false`, backup and device-transfer rules exclude everything.
- Network security config: HTTPS only. The only host contacted is api.anthropic.com.
- API key stored encrypted with a Keystore key, never logged.
- Release APK signed with a key kept only in GitHub secrets.

## Build and delivery
GitHub Actions (`.github/workflows/build.yml`) runs core tests and builds the APK on every push.
Pushes to `main` publish the APK as the `latest` GitHub release, which can be downloaded on the phone.
