package bank;

import bank.account.BankAccount;
import bank.account.CheckingAccount;
import bank.account.Currency;
import bank.auth.AuthService;
import bank.logging.SecurityAuditLogger.Action;
import bank.storage.JsonPersistence;
import bank.storage.StorageException;
import bank.storage.StorageService;
import bank.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatePersistenceTest {

    private static final String ALICE_ACCOUNT = "UA000000000000000001";
    private static final String BOB_ACCOUNT = "UA000000000000000002";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPersistRegistrationAndAccountBeforeExitAndAuditSaveAfterEachOperation() throws Exception {
        Path stateFile = tempDir.resolve("registration-state.json");
        Path logFile = tempDir.resolve("logs/registration.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");

        String password = "private-password";
        InteractiveMain process = startMain(stateFile, logFile);
        process.writeLine("2");
        process.writeLine("alice");
        process.writeLine(password);
        process.writeLine(password);
        process.waitForConsole("User 'alice' was registered.", 10_000);

        Map<String, User> reloaded = new JsonPersistence(stateFile).loadUsers();
        assertTrue(reloaded.containsKey("alice"), process.console());
        assertEquals(0, reloaded.get("alice").getAccountCount());

        process.writeLine("2");
        process.writeLine("UAH");
        process.writeLine("1");
        process.waitForConsole("was added to alice.", 10_000);

        reloaded = new JsonPersistence(stateFile).loadUsers();
        assertEquals(1, reloaded.get("alice").getAccountCount());
        assertTrue(reloaded.get("alice").findAccount(ALICE_ACCOUNT).isPresent());

        process.writeLine("9");
        process.stop();
        assertTrue(process.exitSuccess());

        List<JsonNode> events = readAudit(auditFile);
        assertEquals(6, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(1), "customer-001", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(2), "alice", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(3), "alice", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(4), "alice", "OPEN_ACCOUNT", ALICE_ACCOUNT, "SUCCESS");
        assertEvent(events.get(5), "alice", "SAVE_STATE", null, "SUCCESS");
    }

    @Test
    void shouldPersistTransferAcrossUsersWithBothBalancesAndStatements() throws Exception {
        Path stateFile = tempDir.resolve("transfer-state.json");
        Path logFile = tempDir.resolve("logs/transfer.log");
        String password = "private-password";

        runMain(stateFile, logFile, String.join("\n",
                "2", "alice", password, password,
                "2", "UAH", "1",
                "5", ALICE_ACCOUNT, "200.00",
                "9", ""));
        runMain(stateFile, logFile, String.join("\n",
                "2", "bob", password, password,
                "2", "UAH", "1",
                "9", ""));
        String console = runMain(stateFile, logFile, String.join("\n",
                "1", "alice", password,
                "7", ALICE_ACCOUNT, BOB_ACCOUNT, "75.50",
                "9", ""));

        Map<String, User> reloaded = new JsonPersistence(stateFile).loadUsers();
        BankAccount alice = reloaded.get("alice").findAccount(ALICE_ACCOUNT).orElseThrow();
        BankAccount bob = reloaded.get("bob").findAccount(BOB_ACCOUNT).orElseThrow();

        assertEquals(0, new BigDecimal("124.50").compareTo(alice.getBalance()));
        assertEquals(0, new BigDecimal("75.50").compareTo(bob.getBalance()));
        assertEquals(2, alice.getStatement().size());
        assertEquals(1, bob.getStatement().size());
        assertEquals(0, new BigDecimal("-75.50").compareTo(alice.getStatement().getLast().amount()));
        assertEquals(0, new BigDecimal("75.50").compareTo(bob.getStatement().getLast().amount()));
        assertTrue(console.contains("Transferred 75.50 UAH"));
    }

    @Test
    void shouldRollBackFailedSaveAndKeepPreviousJsonReadable() throws Exception {
        Path stateFile = tempDir.resolve("rollback-state.json");
        SwitchableStorage storage = new SwitchableStorage(stateFile);
        Map<String, User> users = new LinkedHashMap<>();
        User alice = new User("alice", AuthService.hashPassword("password"));
        BankAccount account = new CheckingAccount(ALICE_ACCOUNT, "alice", Currency.UAH);
        account.deposit(new BigDecimal("100.00"), "Initial");
        alice.addAccount(account);
        users.put("alice", alice);
        storage.saveUsers(users);

        LogCapture capture = new LogCapture();
        capture.install();
        storage.failNextSave();
        try {
            boolean saved = commit(users, storage, new AtomicReference<>("alice"),
                    () -> account.deposit(new BigDecimal("999.00"), "Failed deposit"),
                    Action.DEPOSIT, ALICE_ACCOUNT);
            assertFalse(saved);
        } finally {
            capture.uninstall();
        }

        assertTrue(capture.contains("Could not save the bank state"));
        assertFalse(capture.contains("New balance"));
        assertFalse(capture.contains("Bank state saved"));
        assertEquals(0, new BigDecimal("100.00").compareTo(account.getBalance()));
        assertEquals(1, account.getStatement().size());

        Map<String, User> reloaded = new JsonPersistence(stateFile).loadUsers();
        BankAccount restored = reloaded.get("alice").findAccount(ALICE_ACCOUNT).orElseThrow();
        assertEquals(0, new BigDecimal("100.00").compareTo(restored.getBalance()));
        assertEquals(1, restored.getStatement().size());
    }

    @Test
    void shouldExcludeFailedOperationFromFollowingSuccessfulSave() throws Exception {
        Path stateFile = tempDir.resolve("following-save-state.json");
        SwitchableStorage storage = new SwitchableStorage(stateFile);
        Map<String, User> users = new LinkedHashMap<>();
        User alice = new User("alice", AuthService.hashPassword("password"));
        BankAccount account = new CheckingAccount(ALICE_ACCOUNT, "alice", Currency.UAH);
        account.deposit(new BigDecimal("100.00"), "Initial");
        alice.addAccount(account);
        users.put("alice", alice);
        storage.saveUsers(users);

        AtomicReference<String> actor = new AtomicReference<>("alice");
        storage.failNextSave();
        assertFalse(commit(users, storage, actor,
                () -> account.deposit(new BigDecimal("999.00"), "Rejected"),
                Action.DEPOSIT, ALICE_ACCOUNT));

        assertTrue(commit(users, storage, actor,
                () -> account.deposit(new BigDecimal("25.00"), "Accepted"),
                Action.DEPOSIT, ALICE_ACCOUNT));

        Map<String, User> reloaded = new JsonPersistence(stateFile).loadUsers();
        BankAccount restored = reloaded.get("alice").findAccount(ALICE_ACCOUNT).orElseThrow();
        assertEquals(0, new BigDecimal("125.00").compareTo(restored.getBalance()));
        assertEquals(2, restored.getStatement().size());
        assertEquals("Initial", restored.getStatement().get(0).comment());
        assertEquals("Accepted", restored.getStatement().get(1).comment());
        assertFalse(restored.getStatement().stream()
                .anyMatch(transaction -> "Rejected".equals(transaction.comment())));
    }

    @Test
    void shouldAuditFailedRegistrationWithAttemptedUserId() throws Exception {
        Path blockedParent = tempDir.resolve("not-a-directory");
        Files.writeString(blockedParent, "blocks state directory creation");
        Path stateFile = blockedParent.resolve("state.json");
        Path logFile = tempDir.resolve("logs/failed-registration.log");
        String console = runMain(stateFile, logFile, String.join("\n",
                "2", "alice", "password", "password",
                "9", ""));

        assertTrue(console.contains("Could not save the bank state"));
        assertFalse(console.contains("User 'alice' was registered."));

        List<JsonNode> events = readAudit(logFile.resolveSibling("security-audit.jsonl"));
        assertEquals(4, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "FAILURE");
        assertEvent(events.get(1), "customer-001", "SAVE_STATE", null, "FAILURE");
        assertEvent(events.get(2), "alice", "REGISTER", null, "FAILURE");
        assertEvent(events.get(3), "alice", "SAVE_STATE", null, "FAILURE");
    }

    private boolean commit(Map<String, User> users, StorageService storage,
                           AtomicReference<String> actor, Runnable mutation,
                           Action action, String target) throws Exception {
        Method method = Main.class.getDeclaredMethod(
                "mutateAndSave", Map.class, StorageService.class, AtomicReference.class,
                Runnable.class, Action.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(Main.class, users, storage, actor, mutation, action, target);
    }

    private String runMain(Path stateFile, Path logFile, String input) throws Exception {
        Path consoleFile = Files.createTempFile(tempDir, "console-", ".txt");
        Path errorFile = Files.createTempFile(tempDir, "stderr-", ".txt");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process = new ProcessBuilder(java.toString(),
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-Dbank.log.file=" + logFile,
                "-Dbank.audit.file=" + logFile.resolveSibling("security-audit.jsonl"),
                "-cp", System.getProperty("java.class.path"), "bank.Main", stateFile.toString())
                .redirectError(errorFile.toFile())
                .redirectOutput(consoleFile.toFile())
                .start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Console session timed out");
            String console = Files.readString(consoleFile);
            assertEquals(0, process.exitValue(), console);
            assertEquals("", Files.readString(errorFile), "Application logs should use stdout");
            return console;
        } finally {
            process.destroyForcibly();
        }
    }

    private InteractiveMain startMain(Path stateFile, Path logFile) throws Exception {
        Path consoleFile = Files.createTempFile(tempDir, "console-", ".txt");
        Path errorFile = Files.createTempFile(tempDir, "stderr-", ".txt");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process = new ProcessBuilder(java.toString(),
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-Dbank.log.file=" + logFile,
                "-Dbank.audit.file=" + logFile.resolveSibling("security-audit.jsonl"),
                "-cp", System.getProperty("java.class.path"), "bank.Main", stateFile.toString())
                .redirectError(errorFile.toFile())
                .redirectOutput(consoleFile.toFile())
                .start();
        return new InteractiveMain(process, consoleFile, errorFile);
    }

    private static final class InteractiveMain {
        private final Process process;
        private final Path consoleFile;
        private final Path errorFile;
        private final PrintWriter stdin;

        private InteractiveMain(Process process, Path consoleFile, Path errorFile) throws Exception {
            this.process = process;
            this.consoleFile = consoleFile;
            this.errorFile = errorFile;
            this.stdin = new PrintWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        private void writeLine(String line) {
            stdin.println(line);
            stdin.flush();
        }

        private void waitForConsole(String text, long timeoutMillis) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                if (console().contains(text)) {
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("Timed out waiting for console text '" + text + "'. Console:\n" + console());
        }

        private String console() throws Exception {
            return Files.readString(consoleFile);
        }

        private void stop() throws Exception {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Console session timed out");
            assertEquals("", Files.readString(errorFile), "Application logs should use stdout");
        }

        private boolean exitSuccess() {
            return process.exitValue() == 0;
        }
    }

    private List<JsonNode> readAudit(Path auditFile) throws IOException {
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(auditFile)) {
            JsonNode event = objectMapper.readTree(line);
            Set<String> fields = new HashSet<>();
            event.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("timestamp", "actor", "action", "target", "status"), fields);
            Instant.parse(event.get("timestamp").asText());
            events.add(event);
        }
        return events;
    }

    private void assertEvent(JsonNode event, String actor, String action, String target, String status) {
        assertEquals(actor, event.get("actor").asText());
        assertEquals(action, event.get("action").asText());
        assertEquals(target, event.get("target").isNull() ? null : event.get("target").asText());
        assertEquals(status, event.get("status").asText());
    }

    private static final class SwitchableStorage implements StorageService {
        private final StorageService delegate;
        private boolean fail;

        private SwitchableStorage(Path stateFile) {
            delegate = new JsonPersistence(stateFile);
        }

        private void failNextSave() {
            fail = true;
        }

        @Override
        public Map<String, User> loadUsers() {
            return delegate.loadUsers();
        }

        @Override
        public void saveUsers(Map<String, User> users) {
            if (fail) {
                fail = false;
                throw new StorageException("Simulated write failure");
            }
            delegate.saveUsers(users);
        }
    }

    private static final class LogCapture extends Handler {
        private final StringBuilder messages = new StringBuilder();
        private final Logger logger = Logger.getLogger("bank.console.bank.Main");

        private void install() {
            logger.addHandler(this);
        }

        private void uninstall() {
            logger.removeHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            messages.append(record.getMessage()).append('\n');
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private boolean contains(String message) {
            return messages.toString().contains(message);
        }
    }
}
