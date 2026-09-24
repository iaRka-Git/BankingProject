package bank.account;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountNumberGeneratorTest {

    @Test
    void shouldReturnSequentialNumbers() {
        AccountNumberGenerator generator = new AccountNumberGenerator();

        assertEquals("UA000000000000000001", generator.next());
        assertEquals("UA000000000000000002", generator.next());
        assertEquals("UA000000000000000003", generator.next());
    }

    @Test
    void shouldReturnTwentyCharacterString() {
        AccountNumberGenerator generator = new AccountNumberGenerator();

        String number = generator.next();

        assertEquals(20, number.length());
    }

    @Test
    void shouldContainOnlyUAPrefixAndDigits() {
        AccountNumberGenerator generator = new AccountNumberGenerator();

        String number = generator.next();

        assertTrue(number.matches("UA\\d{18}"));
    }

    /**
     * Verifies that the generator produces unique account numbers when called
     * concurrently from multiple threads.
     *
     * <p>Starts 10 threads, each generating 10 000 numbers. All generated numbers
     * are stored in a thread-safe {@link Set}. If any two threads receive the same
     * number, the set size will be smaller than expected and the test will fail.</p>
     */
    @Test
    void shouldGenerateUniqueNumbersConcurrently() throws InterruptedException {
        AccountNumberGenerator generator = new AccountNumberGenerator();
        int threadCount = 10;
        int numbersPerThread = 10_000;
        Set<String> numbers = Collections.synchronizedSet(new HashSet<>());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < numbersPerThread; j++) {
                            numbers.add(generator.next());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
        } finally {
            executor.shutdown();
        }

        assertEquals(threadCount * numbersPerThread, numbers.size());
    }
}
