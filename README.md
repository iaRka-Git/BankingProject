# BankingProject

BankingProject is a Java command-line application for managing bank accounts. It keeps users, balances, and transaction history in a local JSON file and records security events in a separate audit log.

## Features

- Register and sign in through the console.
- Open a checking, credit, or term-deposit account in UAH, EUR, or USD. The application displays an automatically assigned account number in the format `UA` followed by 18 digits.
- List your accounts and see their types, balances, status, and transaction counts.
- Deposit, withdraw, transfer funds between accounts, view account transaction statements, and block or unblock accounts.
- Reload users and accounts (checking, credit, and term-deposit) after a restart. Account numbering continues from the highest saved number in the generated format. Existing numbers are preserved, and duplicate account numbers stop startup with an error identifying the conflict.

The roadmap below covers the remaining consistency and durability improvements.

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
2. Choose **2) Open a new account**, enter `UAH`, and choose **1) Checking account**. Copy the account number shown in the confirmation.
3. Choose **5) Deposit money**, enter that account number, and enter `125.50`.
4. Choose **6) Withdraw money** or **7) Transfer money** to move funds, then **8) View account statement** to inspect transactions.
5. Choose **1) Show my accounts** to check the balance, then **9) Exit** to save the state.
6. Restart the application with the same state file and sign in to see the saved account and balance.

## Project layout

| Path | Contents |
| --- | --- |
| `docs/ARCHITECTURE.md` | High-level system architecture, component relationships, and runtime flows ([Issue #3](https://github.com/iaRka-Git/BankingProject/issues/3)). |
| `docs/PLAN.md` | Initial project coordination and domain contract plan. |
| `src/main/java/bank/Main.java` | Console CLI entry point. |
| `src/main/java/bank/account/` | Account hierarchy (`BankAccount`, `CheckingAccount`, `CreditAccount`, `DepositAccount`), `Currency`, and `AccountNumberGenerator`. |
| `src/main/java/bank/auth/` | Authentication service and exceptions (`AuthService`, `AuthenticationException`). |
| `src/main/java/bank/user/` | Customer domain entity (`User`). |
| `src/main/java/bank/storage/` | JSON persistence (`JsonPersistence`, `StorageService`, `AccountFactory`). |
| `src/main/java/bank/logging/` | Technical log and security audit configuration. |
| `src/test/java/bank/` | Unit and CLI integration tests organized by package (`account/`, `auth/`, `user/`, `storage/`). |

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
| BP-013 | Persist each change before confirming success and handle failures without retaining the rejected change. |
