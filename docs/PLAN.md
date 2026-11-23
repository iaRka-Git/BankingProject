# Banking System — Implementation Plan

Status: approved contract, ready for parallel work.
Target branch: `develop`.

This document is the coordination artifact for the four people working on the
project. Section 2 freezes the shared interfaces. Once Section 2 is agreed,
every task in Section 5 can proceed in parallel without blocking on another
person's code.

---

## 1. Current state of the repository

Two problems must be fixed before feature work begins.

1. **The project does not compile.** `src/main/java/com/github/iarka_git/Main.java`
   calls `IO.println(...)`, which is not part of the Java standard library.
   The entry point is also declared as `static void main()`. Because `Main` is
   an explicitly declared class inside a named package, the simplified entry
   point form does not apply; the signature must be
   `public static void main(String[] args)`.

2. **There is no account model at all.** No `BankAccount`, no `Transaction`,
   no exceptions. Any task that says "extend `BankAccount`" or "call
   `recordTransaction()`" currently refers to code that does not exist.

The original task specification for `CreditAccount` assumed a base class was
already present. It is not. This plan adds that base class as an explicit
task and defines its contract precisely so the other three tasks do not have
to guess it.

### Build prerequisites

Verify these before starting, since a broken build blocks everyone:

- The POM sets `maven.compiler.source` and `maven.compiler.target` to `26`.
  This requires a JDK 26 toolchain. If the installed JDK is older, replace
  both properties with a single `<maven.compiler.release>` set to the
  installed major version. Prefer `release` over `source`/`target` — it also
  validates against the correct platform API.
- No test framework is configured. Task 4 adds JUnit 5 and pins a Surefire
  version. Do not add a second, conflicting test dependency.
- The `groupId` is `com.github.iaRka-Git` while the package is
  `com.github.iarka_git`. This is legal but inconsistent. Changing it is
  optional and low priority; if it changes, it changes in one commit by one
  person, not piecemeal.

---

## 2. Frozen contract

Everything in this section is agreed API. Changing any signature here requires
telling the other three people first, because their code compiles against it.

### 2.1 Package layout

```
src/main/java/com/github/iarka_git/
    Main.java
    model/
        BankAccount.java          (Task 1)
        Transaction.java          (Task 1)
        Money.java                (Task 1)
        CreditAccount.java        (Task 2)
        SavingsAccount.java       (Task 3)
        CheckingAccount.java      (Task 3)
    exception/
        BankingException.java             (Task 1)
        InvalidAmountException.java       (Task 1)
        InsufficientFundsException.java   (Task 1)
        CreditLimitExceededException.java (Task 1)
src/test/java/com/github/iarka_git/
    model/  ...                   (Task 4)
```

### 2.2 `Transaction`

```java
package com.github.iarka_git.model;

public record Transaction(BigDecimal amount, Instant timestamp, String comment) {
    // Compact constructor validates: amount non-null, timestamp non-null,
    // comment non-null (empty allowed).
}
```

`amount` is **signed**. This is the single sign convention for the whole
project:

- Deposit: positive.
- Withdrawal: negative.
- Interest charged to the customer: negative.
- Interest credited to the customer: positive.

`timestamp` is `java.time.Instant`, UTC. Do not use `LocalDateTime` — the
model has no timezone concept and mixing the two invites off-by-timezone bugs.

### 2.3 `BankAccount`

```java
package com.github.iarka_git.model;

public abstract class BankAccount {

    protected BankAccount(String accountId, BigDecimal initialBalance);

    public final String getAccountId();
    public final BigDecimal getBalance();
    public final List<Transaction> getTransactionHistory();

    public final void deposit(BigDecimal amount, String comment);
    public void withdraw(BigDecimal amount, String comment);

    protected BigDecimal minimumAllowedBalance();

    protected final void changeBalance(BigDecimal delta, String comment);
    protected final void changeBalanceUnchecked(BigDecimal delta, String comment);
}
```

Behaviour rules:

- **`deposit(amount, comment)`** is `final`. `amount` must be non-null and
  strictly greater than zero, otherwise `InvalidAmountException`. Adds
  `+amount` to the balance and records a transaction with a positive amount.
  A deposit on an overdrawn account repays the borrowed funds automatically,
  because it is plain addition. No special case is needed.
- **`withdraw(amount, comment)`** is not `final`, so subclasses can tighten
  it. `amount` must be non-null and strictly greater than zero, otherwise
  `InvalidAmountException`. The base implementation rejects any withdrawal
  that would take the balance below `minimumAllowedBalance()` and throws
  `InsufficientFundsException`.
- **`minimumAllowedBalance()`** returns `BigDecimal.ZERO` in the base class.
  Subclasses override it to permit a lower floor. This is the only place the
  floor is defined, so the guard is never duplicated.
- **`changeBalance(delta, comment)`** is the single guarded mutation path. It
  applies `delta`, checks the result against `minimumAllowedBalance()`, throws
  if violated, and records the transaction. All customer-initiated money
  movement goes through here.
- **`changeBalanceUnchecked(delta, comment)`** skips the floor check but still
  records the transaction. It exists for system-posted charges that must not
  be refused — see the interest decision in Section 3. Restricted use; a
  review comment is warranted anywhere it appears outside an interest or fee
  posting method.
- **`getTransactionHistory()`** returns an unmodifiable view. Callers must not
  be able to rewrite bank history.
- **`comment` handling.** If `comment` is null, each operation substitutes its
  own default ("Deposit", "Withdrawal", and so on). Callers never have to pass
  a placeholder string.

Balance is `private`. Subclasses read it through `getBalance()`. Keeping the
field private prevents a subclass from writing to balance directly and
bypassing the floor check, which is the exact failure mode the two
`changeBalance` methods are designed to avoid.

### 2.4 Exceptions

All extend `BankingException`, which extends `RuntimeException`. Unchecked, so
callers are not forced to handle them, but one `catch (BankingException e)`
covers every domain failure.

| Exception | Thrown when |
|---|---|
| `InvalidAmountException` | amount is null, zero, or negative |
| `InsufficientFundsException` | withdrawal would breach the floor on a non-credit account |
| `CreditLimitExceededException` | withdrawal would breach `-creditLimit` on a `CreditAccount` |

`CreditLimitExceededException` replaces the `IllegalStateException` in the
original task description. `IllegalStateException` means "the object is in the
wrong state for this call", which is not what happened: the caller supplied an
amount that is too large. A dedicated type also lets a caller distinguish
"over credit limit" from every other failure without parsing a message string.

`CreditLimitExceededException` carries the attempted amount, the projected
balance, and the credit limit as fields, so the message can be formatted at
the boundary rather than assembled at the throw site.

### 2.5 `Money`

```java
package com.github.iarka_git.model;

public final class Money {
    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static BigDecimal round(BigDecimal value);
    public static BigDecimal percent(BigDecimal base, BigDecimal annualRatePercent);
    public static BigDecimal monthly(BigDecimal annualAmount);
}
```

`percent` divides by 100 and `monthly` divides by 12, **both with an explicit
scale and `RoundingMode`**. This matters: `BigDecimal.divide` without a scale
throws `ArithmeticException` on any non-terminating decimal, and dividing by
12 produces one for almost every realistic rate. Centralising the division
here means no call site can forget the rounding mode.

Comparison rule for the whole project: **always `compareTo`, never `equals`**
on `BigDecimal`. `equals` compares scale as well as value, so
`new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false`. Use
`signum()` for zero and sign tests.

### 2.6 Thread safety

No account class is synchronized. The model is single-threaded by assumption
and that assumption is documented in the class Javadoc of `BankAccount`.

This is a deliberate deferral, not an oversight. It becomes wrong the moment a
scheduler posts interest concurrently with customer withdrawals, because
`changeBalance` is a read-check-write sequence. The follow-up work that adds
scheduling must also add synchronization or move to a transactional store.
Record this in the class Javadoc so it is visible to whoever picks that up.

---

## 3. Decisions the original task left open

Each of these was ambiguous. Each now has one answer. If you disagree with
one, raise it before writing code against it, not after.

**Interest rate is an annual percentage, charged per call.**
`interestRate` of `12` means 12% per year. `chargeCreditInterest()` computes
one month's worth and is intended to be called once per month.

**Fee formula.**
`fee = round(abs(balance) * interestRate / 100 / 12)`

Applied only when `balance` is negative. If the rounded fee is `0.00`, no
transaction is recorded — an empty posting is noise in the history.

**Interest may push the balance past the credit limit.**
An interest charge is not rejected for breaching `-creditLimit`. Refusing it
would let an account permanently escape accrued fees, which is how a bank
loses money it is owed. This is why `changeBalanceUnchecked` exists.

The consequence is intentional and must be tested: a `CreditAccount` can end
up with a balance below `-creditLimit` after an interest posting. What that
blocks is *further withdrawals*, not the posting itself.

**No day-count accrual yet.**
Real interest accrues on actual days elapsed at a daily rate. This plan uses a
flat monthly division because there is no accrual-period tracking and no
scheduler to define the period boundary. Adding day-count accuracy means
storing the last accrual date and computing elapsed days; that is a separate
task and should not be smuggled into Task 2.

**No scheduler.**
`chargeCreditInterest()` is called explicitly by a test, a `main`, or a future
job runner. Nothing in this plan starts a timer.

**`chargeCreditInterest()` returns the fee charged.**
Signature is `public BigDecimal chargeCreditInterest()`. It returns the
positive fee amount, or `BigDecimal.ZERO` when the balance is not negative or
the fee rounds to zero. A `void` method cannot be asserted on without reading
back the balance and re-deriving the number, which duplicates the formula in
every test.

**Constructor validation.**
`creditLimit` must be non-null and non-negative. `interestRate` must be
non-null and non-negative. Violations throw `InvalidAmountException`.
A negative credit limit would mean the account may not be overdrawn at all
while still claiming to be a credit account; a negative rate would mean
paying the customer to borrow. Both are configuration errors and should fail
at construction, not at first use.

**`creditLimit` of zero is allowed.** It produces a `CreditAccount` that
behaves exactly like a non-overdraft account. That is a legitimate
configuration and a useful test case, not an error.

---

## 4. Definition of done

A task is done when all of the following hold:

- `mvn clean test` passes from a clean checkout, with no new warnings.
- Every public class and every non-obvious method has Javadoc stating its
  contract, including which exceptions it throws.
- Every acceptance criterion in the task's own list has at least one test
  that names it.
- No `System.out.println` used for reporting state. Tests assert; they do not
  print.
- The PR description states which decisions from Section 3 the code relies on.

---

## 5. Tasks

Four tasks, one per person. Fill in the owner column before starting.

| Task | Owner | Depends on |
|---|---|---|
| 1. Foundation: `BankAccount`, `Transaction`, exceptions, `Money` | _(unassigned)_ | none |
| 2. `CreditAccount` | _(unassigned)_ | Section 2 contract |
| 3. `SavingsAccount` and `CheckingAccount` | _(unassigned)_ | Section 2 contract |
| 4. Build, test harness, `Main` fix, CI | _(unassigned)_ | none |

Tasks 2 and 3 depend on the **contract in Section 2**, not on Task 1's merged
code. Write against the signatures as documented. If Task 1 lands and differs,
that is a contract change and gets raised with all four people.

Tasks 1 and 4 have no dependencies and should start immediately.

### Task 1 — Foundation

Deliver `BankAccount`, `Transaction`, `Money`, and the four exception classes
exactly as specified in Section 2.

Acceptance criteria:

- [ ] `BankAccount` is abstract; balance is `private` with a public getter.
- [ ] `deposit` is `final`, rejects null and non-positive amounts with
      `InvalidAmountException`, records a positive transaction.
- [ ] `withdraw` rejects null and non-positive amounts with
      `InvalidAmountException`, rejects floor breaches with
      `InsufficientFundsException`, records a negative transaction.
- [ ] `minimumAllowedBalance()` returns `BigDecimal.ZERO` by default and is
      `protected`, overridable.
- [ ] `changeBalance` enforces the floor; `changeBalanceUnchecked` does not.
      Both record a transaction.
- [ ] `getTransactionHistory()` returns an unmodifiable list. Attempting to
      modify it throws.
- [ ] A null comment is replaced by an operation-specific default, never
      stored as null.
- [ ] `Money.round`, `Money.percent`, and `Money.monthly` use scale 2 and
      `HALF_UP`, and do not throw on rates that produce non-terminating
      decimals. Test `interestRate` of `7` and `12` specifically.
- [ ] Class Javadoc states the single-threaded assumption.

### Task 2 — `CreditAccount`

Deliver `CreditAccount extends BankAccount` with `creditLimit` and
`interestRate`.

Acceptance criteria:

- [ ] Constructor calls `super(accountId, initialBalance)`, validates both new
      fields per Section 3, and stores them.
- [ ] `minimumAllowedBalance()` returns `creditLimit.negate()`.
- [ ] Withdrawal that lands exactly on `-creditLimit` succeeds. Test the
      boundary, not just a value comfortably inside it.
- [ ] Withdrawal that would pass `-creditLimit` throws
      `CreditLimitExceededException`, and the balance is unchanged after the
      throw. Verify no partial mutation.
- [ ] Default withdrawal comment is "Credit funds withdrawal" when the caller
      passes null.
- [ ] `chargeCreditInterest()` returns `BigDecimal.ZERO` and records nothing
      when the balance is zero or positive.
- [ ] `chargeCreditInterest()` on a negative balance deducts
      `round(abs(balance) * interestRate / 100 / 12)` and records a
      transaction commented "Interest charge on utilized credit".
- [ ] The interest posting is permitted to move the balance below
      `-creditLimit`. Test this explicitly.
- [ ] A subsequent withdrawal on an account already past `-creditLimit` from
      interest is refused.
- [ ] `interestRate` of zero produces a zero fee and records nothing.
- [ ] `creditLimit` of zero behaves as a non-overdraft account: any withdrawal
      below zero is refused.
- [ ] Interest is not compounded within a single call, and a second call in the
      same period charges on the new balance. Document that calling it twice in
      one month is a caller error, not something the class prevents.

### Task 3 — `SavingsAccount` and `CheckingAccount`

Two concrete subclasses, included so that account-type work is not
concentrated in one person and so the base class is proven against more than
one subclass.

`CheckingAccount`: floor is zero, no interest, may carry a per-transaction or
monthly fee. `SavingsAccount`: floor is zero, accrues interest on a positive
balance via `accrueInterest()`, which mirrors `chargeCreditInterest()` in
shape and returns the credited amount.

Savings credit formula: `round(balance * interestRate / 100 / 12)`, applied
only when `balance` is positive, recorded as a positive transaction commented
"Interest credited on savings balance".

Acceptance criteria:

- [ ] `CheckingAccount` refuses any withdrawal that would take the balance
      below zero, with `InsufficientFundsException`.
- [ ] `SavingsAccount.accrueInterest()` returns `BigDecimal.ZERO` and records
      nothing when the balance is zero or negative.
- [ ] `SavingsAccount.accrueInterest()` on a positive balance credits the
      computed amount and returns it.
- [ ] Both classes reject a negative `interestRate` at construction where the
      field applies.
- [ ] A fee posted through `changeBalanceUnchecked` cannot take a
      `CheckingAccount` below zero silently — decide and document whether fees
      are allowed to overdraw. Recommended: refuse the fee and log, because a
      fee that overdraws a checking account surprises the customer.
- [ ] Neither class duplicates floor logic from `BankAccount`; both express it
      by overriding `minimumAllowedBalance()` or not overriding it at all.

### Task 4 — Build, tests, and CI

Deliver a build that runs the test suite, a fixed entry point, and continuous
integration.

Acceptance criteria:

- [ ] `Main.java` compiles: `IO.println` replaced with `System.out.println`,
      entry point is `public static void main(String[] args)`.
- [ ] POM adds JUnit 5 (`junit-jupiter` aggregate artifact, `test` scope) and
      pins `maven-surefire-plugin` to a current 3.x version. An unpinned
      Surefire can silently skip JUnit 5 tests on older Maven defaults.
- [ ] POM replaces `maven.compiler.source` and `maven.compiler.target` with a
      single `maven.compiler.release`, set to the JDK the team actually has.
      Confirm with the other three before choosing the number.
- [ ] `mvn clean test` runs and reports tests, not zero tests. Verify the
      count is non-zero — a suite that silently discovers nothing passes while
      testing nothing.
- [ ] Test base class or helper for constructing accounts with a fixed
      `Clock`/`Instant`, so transaction timestamps are deterministic and
      assertions do not flake.
- [ ] CI workflow that runs `mvn clean test` on every push and pull request to
      `develop` and `main`.
- [ ] `.gitignore` covers `target/` and IDE files.
- [ ] One test per acceptance criterion in Tasks 1, 2, and 3, named after the
      criterion. Coordinate with those owners so coverage is not duplicated
      or missed.

Task 4 owns the POM. Nobody else edits it. Concurrent POM edits across four
branches guarantee a merge conflict in the one file everyone needs.

---

## 6. Working agreement

**Branches.** `develop` is the integration branch and holds this plan. Each
person branches from `develop` as `feature/<task-number>-<short-topic>`, for
example `feature/2-credit-account`, and opens a pull request back into
`develop`. `main` receives `develop` through a release pull request once the
four tasks are merged. Do not commit directly to `develop`.

**Commit messages.** Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`,
`build:`, `refactor:`. Imperative mood, subject under 72 characters, body
explaining why rather than what.

**Pull requests.** One task per pull request. Keep them small enough to review
in one sitting; a pull request nobody can finish reading is a pull request
nobody reviews. The description states which Section 3 decisions the code
depends on.

**Contract changes.** Section 2 is frozen. A change to any signature there is
announced to all four people before it is written, because three other
branches compile against it.

**Review.** At least one approval from someone other than the author before
merge. Task 1 and Task 4 should be reviewed by two people, since every other
branch depends on the first and the whole team depends on the second.
