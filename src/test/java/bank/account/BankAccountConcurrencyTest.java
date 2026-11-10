package bank.account;

import bank.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void shouldHandleConcurrentDepositsCorrectly() throws InterruptedException {
        CheckingAccount account = createCheckingAccount("UA-CONC-01");
        int threadCount = 100;
        BigDecimal depositAmount = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    account.deposit(depositAmount, "Concurrent deposit");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All deposit threads should finish within timeout");
        assertEquals(INITIAL_BALANCE, account.getBalance());
        assertEquals(100, account.getStatement().size());
    }

    @Test
    @DisplayName("Should prevent double spending on concurrent withdrawals exceeding balance")
    void shouldPreventDoubleSpendingOnConcurrentWithdrawals() throws InterruptedException {
        CheckingAccount account = createCheckingAccount("UA-CONC-02");
        account.deposit(INITIAL_BALANCE, "Initial balance");

        int threadCount = 10;
        BigDecimal withdrawAmount = INITIAL_BALANCE;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    account.withdraw(withdrawAmount, "Concurrent withdraw");
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException | IllegalStateException e) {
                    failCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All withdrawal threads should finish within timeout");
        assertEquals(1, successCount.get(), "Only one thread should successfully withdraw full balance");
        assertEquals(9, failCount.get(), "Remaining threads should fail due to insufficient funds");
        assertEquals(new BigDecimal("0.00"), account.getBalance());
    }

    @Test
    @DisplayName("Should execute cross transfers concurrently without deadlocks")
    void shouldExecuteConcurrentTransfersWithoutDeadlock() throws InterruptedException {
        transferAccountA.deposit(INITIAL_BALANCE);
        transferAccountB.deposit(INITIAL_BALANCE);

        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(iterations * 2);

        // Submitting bidirectional transfers simultaneously (A -> B and B -> A)
        for (int i = 0; i < iterations; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferAccountA.transfer(transferAccountB, new BigDecimal("1.00"), "Transfer A->B");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferAccountB.transfer(transferAccountA, new BigDecimal("1.00"), "Transfer B->A");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Transfers must not result in a deadlock");
        BigDecimal totalBalance = transferAccountA.getBalance().add(transferAccountB.getBalance());
        assertEquals(new BigDecimal("2000.00"), totalBalance, "Total money across accounts must remain constant");
    }

    @Test
    @DisplayName("Should handle concurrent interest accruals and withdrawals safely on deposit account")
    void shouldHandleConcurrentInterestAndWithdrawalOnDepositAccount() throws InterruptedException {
        depositAccount.deposit(INITIAL_BALANCE);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (index % 2 == 0) {
                        depositAccount.accrueInterest();
                    } else {
                        depositAccount.withdraw(new BigDecimal("10.00"), "Withdrawal");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Concurrent interest and withdrawal operations should complete successfully");
    }

    private CheckingAccount createCheckingAccount(String accountNumber) {
        return new CheckingAccount(accountNumber, OWNER_ID, Currency.EUR);
    }
}