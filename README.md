# BankingProject

BankingProject is a Java command-line application for managing bank accounts. It keeps users, balances, and transaction history in a local JSON file and records security events in a separate audit log.

## Features

- Register and sign in through the console.
- Open a checking account in UAH, EUR, or USD. The application displays an automatically assigned account number in the format `UA` followed by 18 digits.
- List your accounts and see their balances, status, and transaction counts.
- Deposit money and block or unblock accounts.
- Reload users and checking accounts after a restart. Account numbering continues from the highest saved number in the generated format. Existing numbers are preserved, and duplicate account numbers stop startup with an error identifying the conflict.

The domain model includes withdrawal and transfer methods, credit limits, and term-deposit rules. The roadmap below covers exposing those operations and account types through the console.

## Tech stack

| Tool | Use |
| --- | --- |
| Java 21 | Console application and domain model. Monetary values use `BigDecimal`. |
| Maven | Dependency management, builds, and test execution. |
| Jackson Databind | JSON serialization and deserialization. |
| `java.util.logging` | Console messages, technical logs, and security audit events. |
| JUnit Jupiter and Maven Surefire | Unit and CLI integration tests. |

## Getting started

Install JDK 21 and Maven, then run the following from the repository root. This builds the project, runs the tests, and copies dependencies into `target/dependency/`.

```sh
mvn package dependency:copy-dependencies
```

Run the CLI in PowerShell:

```powershell
java -cp "target/classes;target/dependency/*" bank.Main
```

On macOS or Linux:

```sh
java -cp "target/classes:target/dependency/*" bank.Main
```

The optional first argument sets the JSON state file. Append `data/demo-state.json` to the launch command to keep the demo data separate. The default is `data/bank-state.json`. The CLI saves state on exit.

### Try the CLI

1. Choose **2) Register a new user**, enter a user ID and password, then confirm the password. Registration starts a signed-in session.
2. Choose **2) Open a new checking account** and enter `UAH`. Copy the account number shown in the confirmation.
3. Choose **5) Deposit money**, enter that account number, and enter `125.50`.
4. Choose **1) Show my accounts** to check the balance, then **6) Exit** to save the state.
5. Restart the application with the same state file and sign in to see the saved account and balance.

## Project layout

| Path | Contents |
| --- | --- |
| `src/main/java/bank/` | CLI entry point, authentication, users, and core account classes. |
| `src/main/java/bank/account/` | Account number generator and term-deposit account. |
| `src/main/java/bank/storage/` | JSON persistence and account factories. |
| `src/main/java/bank/logging/` | Technical log and security audit configuration. |
| `src/test/java/bank/` | Tests for account rules, authentication, persistence, and CLI flows. |

The default technical log is `logs/bank.log`. Security events are written as JSON Lines to `logs/security-audit.jsonl`. Local state and logs are Git-ignored.

## Tests

```sh
mvn clean test
```

The suite covers account rules, authentication, JSON round trips, and console sessions, including account number generation across users and restarts.

## Roadmap

| Work item | Planned behavior |
| --- | --- |
| [PR #26](https://github.com/iaRka-Git/BankingProject/pull/26) | Synchronize concurrent account operations and establish safe lock ordering for transfers. |
| BP-011 | Apply consistent precision validation and rounding so statement entries match balance changes. |
| BP-012 | Add withdrawals, transfers, and transaction statements to the CLI. |
| BP-013 | Persist each change before confirming success and handle failures without retaining the rejected change. |
| BP-014 | Open credit and term-deposit accounts in the CLI and restore their type-specific settings from JSON. |
