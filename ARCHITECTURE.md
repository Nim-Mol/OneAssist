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

### Income
Income categories: Salary, Freelance & side income, Benefits & allowances (toeslagen, kinderbijslag),
Refunds & tax returns, Gifts received, Interest & investments, Other income (id `income`). Income rules
only apply to money coming in. Incoming bank transfers (`peerToPeer`) count as transfers until the
user marks them as income: that sets an income category and clears `peerToPeer`. "Always treat
payments from X as ..." saves a learned income rule (id `learned-income:<pattern>`, ranked just above
other learned rules), so later imports from that payer are income too; payments *to* X are unaffected.
The Overview shows income per income category and the savings rate, (income - spending) / income.

### Tags
Free-form user tags on transactions (`holiday-2026`, `work`, `gift`): trimmed, lowercase, other
characters become `-`, at most 30 characters and 10 per transaction (`core/Tags`). Stored in one
column as `,a,b,` so a tag filter is an exact `instr` match. Transactions can be filtered by tag;
Insights lists spending and income per tag for the month.

### Manual entry
"Add transaction" (FAB on Transactions): income or expense, amount, date, name, account (ING, Revolut,
credit card or Cash), category, tags and an optional note. Source `MANUAL`; manual rows skip de-dup on
insert and are never replaced or removed by it (a matching import counts as a duplicate). Manual income
can be marked "repeats monthly": the latest such entry per payer and category is shown on the Overview
as expected income for every later month until it is switched off, with "received" once matching
income arrives. Notes stay on the device.

## Assistant (optional)
- Model: Claude Haiku via the Anthropic Messages API. The user pastes their own API key in Settings.
- The prompt contains only aggregates: monthly totals per category, income per income category and
  savings rate, totals per user tag (sanitized), top merchants by spend, subscriptions, detected leaks.
  No IBANs, account numbers, people's names, payers of income, notes or raw descriptions.
- Read-only by design: the assistant has no tools and cannot act. Merchant names are wrapped as
  data, so prompt injection through them cannot trigger anything.
- Cost target: a few cents per conversation.

## Security baseline (owner: `security` agent)
- Database encrypted with SQLCipher; the key is random, wrapped by an Android Keystore key.
- Biometric / device credential lock on launch and after 60 s in the background. FLAG_SECURE on.
- `allowBackup=false`, backup and device-transfer rules exclude everything.
- Network security config: HTTPS only. The app itself only contacts api.anthropic.com (ML Kit text recognition may send anonymous Google usage telemetry; no financial data).
- API key stored encrypted with a Keystore key, never logged.
- Release APK signed with a key kept only in GitHub secrets.

## Build and delivery
GitHub Actions (`.github/workflows/build.yml`) runs core tests and builds the APK on every push.
Pushes to `main` publish the APK as the `latest` GitHub release, which can be downloaded on the phone.
