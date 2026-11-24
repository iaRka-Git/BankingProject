package bank.account;

import bank.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankAccountConcurrencyTest {

    private static final String OWNER_ID = "owner-1";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

    private final CheckingAccount transferAccountA = new CheckingAccount("UA-LOCK-01", OWNER_ID, Currency.UAH);
    private final CheckingAccount transferAccountB = new CheckingAccount("UA-LOCK-02", "owner-2", Currency.UAH);

    private final DepositAccount depositAccount = new DepositAccount(
            "UA-DEP-01", OWNER_ID, Currency.UAH,
            LocalDate.now().minusDays(1), false, new BigDecimal("0.10")
    );

    @Test
    @DisplayName("Should prevent data loss during concurrent deposits (Lost Update check)")
    void shouldHandleConcurrentDepositsCorrectly() throws Exception {
        CheckingAccount account = createCheckingAccount("UA-CONC-01");
        int threadCount = 100;
        BigDecimal depositAmount = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit((Callable<Void>) () -> {
                    startLatch.await();
                    account.deposit(depositAmount, "Concurrent deposit");
                    return null;
                }));
            }

            startLatch.countDown();
            awaitAll(futures, 5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(INITIAL_BALANCE, account.getBalance());
        assertEquals(100, account.getStatement().size());
    }

    @Test
    @DisplayName("Should prevent double spending on concurrent withdrawals exceeding balance")
    void shouldPreventDoubleSpendingOnConcurrentWithdrawals() throws Exception {
        CheckingAccount account = createCheckingAccount("UA-CONC-02");
        account.deposit(INITIAL_BALANCE, "Initial balance");

        int threadCount = 10;
        BigDecimal withdrawAmount = INITIAL_BALANCE;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit((Callable<Void>) () -> {
                    startLatch.await();
                    try {
                        account.withdraw(withdrawAmount, "Concurrent withdraw");
                        successCount.incrementAndGet();
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        // Expected failure due to insufficient funds; other exceptions will propagate via Future.get()
                        failCount.incrementAndGet();
                    }
                    return null;
                }));
            }

            startLatch.countDown();
            awaitAll(futures, 5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, successCount.get(), "Only one thread should successfully withdraw full balance");
        assertEquals(9, failCount.get(), "Remaining threads should fail due to insufficient funds");
        assertEquals(new BigDecimal("0.00"), account.getBalance());
        assertEquals(2, account.getStatement().size(), "Statement should contain initial deposit and 1 withdrawal");
    }

    @Test
    @DisplayName("Should execute cross transfers concurrently without deadlocks")
    void shouldExecuteConcurrentTransfersWithoutDeadlock() throws Exception {
        transferAccountA.deposit(INITIAL_BALANCE);
        transferAccountB.deposit(INITIAL_BALANCE);

        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(iterations * 2);

        try {
            for (int i = 0; i < iterations; i++) {
                futures.add(executor.submit((Callable<Void>) () -> {
                    startLatch.await();
                    transferAccountA.transfer(transferAccountB, new BigDecimal("1.00"), "Transfer A->B");
                    return null;
                }));

                futures.add(executor.submit((Callable<Void>) () -> {
                    startLatch.await();
                    transferAccountB.transfer(transferAccountA, new BigDecimal("1.00"), "Transfer B->A");
                    return null;
                }));
            }

            startLatch.countDown();
            awaitAll(futures, 5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(INITIAL_BALANCE, transferAccountA.getBalance());
        assertEquals(INITIAL_BALANCE, transferAccountB.getBalance());
        // 1 initial deposit + 100 outgoing + 100 incoming transfers = 201 records
        assertEquals(201, transferAccountA.getStatement().size(), "All 200 transfers must be recorded on A");
        assertEquals(201, transferAccountB.getStatement().size(), "All 200 transfers must be recorded on B");
    }

    @Test
    @DisplayName("Should handle concurrent interest accruals and withdrawals safely on deposit account")
    void shouldHandleConcurrentInterestAndWithdrawalOnDepositAccount() throws Exception {
        depositAccount.deposit(INITIAL_BALANCE);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                futures.add(executor.submit((Callable<Void>) () -> {
                    startLatch.await();
                    if (index % 2 == 0) {
                        depositAccount.accrueInterest();
                    } else {
                        depositAccount.withdraw(new BigDecimal("10.00"), "Withdrawal");
                    }
                    return null;
                }));
            }

            startLatch.countDown();
            awaitAll(futures, 5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // 1 initial deposit + 10 interest accruals + 10 withdrawals = 21 operations
        assertEquals(21, depositAccount.getStatement().size(), "All 20 operations must succeed");
    }

    @Test
    @DisplayName("Should leave both accounts unchanged when transfer to blocked recipient fails")
    void shouldNotDebitSenderWhenRecipientIsBlocked() {
        CheckingAccount sender = createCheckingAccount("UA-TR-01");
        CheckingAccount recipient = createCheckingAccount("UA-TR-02");

        sender.deposit(new BigDecimal("100.00"), "Initial");
        recipient.setActive(false);

        assertThrows(IllegalStateException.class, () ->
                sender.transfer(recipient, new BigDecimal("20.00"), "Blocked transfer")
        );

        assertEquals(new BigDecimal("100.00"), sender.getBalance(), "Sender balance must remain unchanged");
        assertEquals(1, sender.getStatement().size(), "Sender statement must not record failed transfer");
        assertEquals(new BigDecimal("0.00"), recipient.getBalance(), "Recipient balance must remain unchanged");
        assertTrue(recipient.getStatement().isEmpty(), "Recipient statement must remain empty");
    }

    @Test
    @DisplayName("Should throw exception when transferring to the exact same account instance")
    void shouldThrowWhenTransferringToSelf() {
        CheckingAccount account = createCheckingAccount("UA-SELF-01");
        account.deposit(new BigDecimal("100.00"));

        assertThrows(IllegalArgumentException.class, () ->
                account.transfer(account, new BigDecimal("50.00"), "Self transfer")
        );

        assertEquals(new BigDecimal("100.00"), account.getBalance());
    }

    @Test
    @DisplayName("Should throw exception when transferring between distinct accounts with the same number")
    void shouldThrowWhenTransferringBetweenAccountsWithSameNumber() {
        CheckingAccount accountA = new CheckingAccount("UA-SAME-01", "owner-1", Currency.UAH);
        CheckingAccount accountB = new CheckingAccount("UA-SAME-01", "owner-2", Currency.UAH);
        accountA.deposit(new BigDecimal("100.00"));

        assertThrows(IllegalArgumentException.class, () ->
                accountA.transfer(accountB, new BigDecimal("50.00"), "Same number transfer")
        );

        assertEquals(new BigDecimal("100.00"), accountA.getBalance());
        assertEquals(new BigDecimal("0.00"), accountB.getBalance());
    }

    @Test
    @DisplayName("Should restore state atomically with respect to account lock")
    void shouldRestoreStateAtomically() throws Exception {
        CheckingAccount account = createCheckingAccount("UA-RESTORE-01");
        List<BankAccount.Transaction> stateATx = List.of(
                new BankAccount.Transaction(new BigDecimal("100.00"), LocalDateTime.now(), "Tx 1")
        );
        List<BankAccount.Transaction> stateBTx = List.of(
                new BankAccount.Transaction(new BigDecimal("200.00"), LocalDateTime.now(), "Tx 1"),
                new BankAccount.Transaction(new BigDecimal("300.00"), LocalDateTime.now(), "Tx 2")
        );

        account.restoreState(new BigDecimal("100.00"), true, stateATx);

        int iterations = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(2);

        try {
            // Thread 1: repeatedly switches account state between A (100, 1 transaction) and B (500, 2 transactions)
            futures.add(executor.submit((Callable<Void>) () -> {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    if (i % 2 == 0) {
                        account.restoreState(new BigDecimal("500.00"), true, stateBTx);
                    } else {
                        account.restoreState(new BigDecimal("100.00"), true, stateATx);
                    }
                }
                return null;
            }));

            // Thread 2: reads balance and statement under the account lock — they must always match the same state
            futures.add(executor.submit((Callable<Void>) () -> {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    synchronized (account) {
                        BigDecimal bal = account.getBalance();
                        int txCount = account.getStatement().size();
                        if (bal.compareTo(new BigDecimal("100.00")) == 0) {
                            assertEquals(1, txCount, "Inconsistent state: balance 100 with " + txCount + " transactions");
                        } else if (bal.compareTo(new BigDecimal("500.00")) == 0) {
                            assertEquals(2, txCount, "Inconsistent state: balance 500 with " + txCount + " transactions");
                        }
                    }
                }
                return null;
            }));

            startLatch.countDown();
            awaitAll(futures, 5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitAll(List<Future<?>> futures, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Future<?> future : futures) {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            future.get(remaining, TimeUnit.NANOSECONDS);
        }
    }

    private CheckingAccount createCheckingAccount(String accountNumber) {
        return new CheckingAccount(accountNumber, OWNER_ID, Currency.EUR);
    }
}