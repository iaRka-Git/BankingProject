package bank;

import bank.account.*;
import bank.auth.AuthService;
import bank.storage.JsonPersistence;
import bank.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainLoggingTest {

    private static final String FIRST_ACCOUNT_NUMBER = "UA000000000000000001";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSeparateUiTechnicalLogsAndStructuredAuditAcrossRestarts() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        String password = "private-test-password";
        String input = String.join("\n", "unknown", "2", "клієнт", password, password,
                "2", "UAH", "1", "5", FIRST_ACCOUNT_NUMBER, "125.50",
                "3", FIRST_ACCOUNT_NUMBER, "5", FIRST_ACCOUNT_NUMBER, "999.99",
                "4", FIRST_ACCOUNT_NUMBER, "1", "9", "");

        String console = runMain(stateFile, logFile, input);
        String firstLog = Files.readString(logFile);

        assertTrue(console.contains("Choose an option:"));
        assertTrue(console.contains("Password:"));
        assertTrue(console.contains("[WARNING] Unknown option"));
        assertTrue(console.contains("Main menu"));
        assertTrue(console.contains("Welcome, клієнт!"));
        assertFalse(console.contains("bank.Main"), "Console should omit class and method names");
        assertFalse(console.contains("INFO:"), "Menu should omit log metadata");
        assertTrue(console.contains(FIRST_ACCOUNT_NUMBER + " | CHECKING | 125.50 UAH | ACTIVE | 1 transaction(s)"));
        assertFalse(firstLog.contains("lambda$"), "Log headers should omit generated method names");
        assertFalse(firstLog.contains("Choose an option"));
        assertFalse(firstLog.contains("Password:"));
        assertFalse(firstLog.contains("Unknown option"));
        assertFalse(firstLog.contains("клієнт"));
        assertFalse(firstLog.contains(FIRST_ACCOUNT_NUMBER));
        assertFalse(firstLog.contains("125.50"));
        assertFalse(firstLog.contains("999.99"));
        assertFalse(firstLog.contains(password));
        assertFalse(firstLog.contains("secret123"));

        String firstAudit = Files.readString(auditFile);
        List<JsonNode> events = readAudit(auditFile);
        assertEquals(13, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(1), "customer-001", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(2), "клієнт", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(3), "клієнт", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(4), "клієнт", "OPEN_ACCOUNT", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(5), "клієнт", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(6), "клієнт", "DEPOSIT", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(7), "клієнт", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(8), "клієнт", "BLOCK_ACCOUNT", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(9), "клієнт", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(10), "клієнт", "DEPOSIT", FIRST_ACCOUNT_NUMBER, "FAILURE");
        assertEvent(events.get(11), "клієнт", "UNBLOCK_ACCOUNT", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(12), "клієнт", "SAVE_STATE", null, "SUCCESS");
        assertFalse(firstAudit.contains(password));
        assertFalse(firstAudit.contains("secret123"));
        assertFalse(firstAudit.contains("125.50"));
        assertFalse(firstAudit.contains("999.99"));
        assertFalse(firstAudit.contains("Choose an option"));
        assertFalse(console.contains("\"action\""), "Audit records must not propagate to the console");

        String nextInput = String.join("\n", "2", "клієнт", password, password,
                "1", "клієнт", "wrong-password", "клієнт", password,
                "5", "UA404", "125.50", "9", "");
        runMain(stateFile, logFile, nextInput);

        String secondLog = Files.readString(logFile);
        assertTrue(secondLog.startsWith(firstLog), "Restart must append to the existing log");
        assertTrue(secondLog.length() > firstLog.length());
        assertFalse(secondLog.contains("клієнт"));
        assertFalse(secondLog.contains("Login failed"));
        assertFalse(secondLog.contains("wrong-password"));
        assertTrue(Files.readString(auditFile).startsWith(firstAudit));
        events = readAudit(auditFile);
        assertEquals(17, events.size());
        assertEvent(events.get(13), "клієнт", "REGISTER", null, "FAILURE");
        assertEvent(events.get(14), "клієнт", "LOGIN", null, "FAILURE");
        assertEvent(events.get(15), "клієнт", "LOGIN", null, "SUCCESS");
        assertEvent(events.get(16), "клієнт", "DEPOSIT", "UA404", "FAILURE");
    }

    @Test
    void shouldLogStorageErrorsWithStackTrace() throws Exception {
        Path stateFile = tempDir.resolve("corrupted.json");
        Path logFile = tempDir.resolve("errors/bank.log");
        Files.writeString(stateFile, "{ invalid JSON");

        String console = runMain(stateFile, logFile, "");
        String log = Files.readString(logFile);

        assertTrue(console.contains("[SEVERE] Could not load the bank state"));
        assertFalse(console.contains("at bank."), "Stack traces should stay in the log file");
        assertTrue(log.contains("[SEVERE] bank.Main - Could not load the bank state"));
        assertTrue(log.contains("bank.storage.StorageException"));
        assertTrue(log.contains("at bank.storage.JsonPersistence.loadUsers"));
    }

    @Test
    void shouldAuditFailedStateSave() throws Exception {
        Path blockedParent = tempDir.resolve("not-a-directory");
        Files.writeString(blockedParent, "blocks state directory creation");
        Path logFile = tempDir.resolve("logs/bank.log");

        String console = runMain(blockedParent.resolve("state.json"), logFile,
                "1\ncustomer-001\nsecret123\n9\n");

        List<JsonNode> events = readAudit(logFile.resolveSibling("security-audit.jsonl"));
        assertEquals(3, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "FAILURE");
        assertEvent(events.get(1), "customer-001", "SAVE_STATE", null, "FAILURE");
        assertEvent(events.get(2), "customer-001", "LOGIN", null, "FAILURE");
        assertTrue(console.contains("Could not save the bank state"));
        assertFalse(console.contains("Bank state saved"));
        assertFalse(Files.readString(logFile).contains("Bank state saved"));
    }

    @Test
    void shouldNotAuditMenuNavigation() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        runMain(stateFile, logFile, "3\n");
        List<JsonNode> before = readAudit(auditFile);

        String console = runMain(stateFile, logFile, "unknown\n3\n");

        assertTrue(console.contains("Choose an option:"));
        assertTrue(console.contains("[WARNING] Unknown option"));
        List<JsonNode> after = readAudit(auditFile);
        assertEquals(before, after.subList(0, before.size()));
        assertEquals(before.size(), after.size());
        assertFalse(Files.readString(logFile).contains("Unknown option"));
    }

    @Test
    void shouldEscapeIdentifiersInStructuredAuditRecords() throws Exception {
        Path logFile = tempDir.resolve("logs/bank.log");
        Path stateFile = tempDir.resolve("state.json");
        String actor = "user\"\\\tidentifier";
        String accountNumber = "UA\"\\\t001";
        String password = "private-password";
        User user = new User(actor, AuthService.hashPassword(password));
        user.addAccount(new CheckingAccount(accountNumber, actor, Currency.UAH));
        new JsonPersistence(stateFile).saveUsers(Map.of(actor, user));
        String input = String.join("\n", "1", actor, password, "5", accountNumber, "1", "9", "");

        runMain(stateFile, logFile, input);

        List<JsonNode> events = readAudit(logFile.resolveSibling("security-audit.jsonl"));
        assertEquals(3, events.size());
        assertEvent(events.get(0), actor, "LOGIN", null, "SUCCESS");
        assertEvent(events.get(1), actor, "DEPOSIT", accountNumber, "SUCCESS");
        assertEvent(events.get(2), actor, "SAVE_STATE", null, "SUCCESS");
    }

    @Test
    void shouldAssignUniqueAccountNumbersAcrossUsersAndRestarts() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        String password = "private-password";
        String legacyNumber = "UA001";
        String highestNumber = "UA000000000000000042";
        User alice = new User("alice", AuthService.hashPassword(password));
        alice.addAccount(new CheckingAccount(highestNumber, "alice", Currency.UAH));
        alice.addAccount(new CheckingAccount(legacyNumber, "alice", Currency.UAH));
        JsonPersistence storage = new JsonPersistence(stateFile);
        storage.saveUsers(Map.of("alice", alice));

        String firstRun = runMain(stateFile, logFile,
                String.join("\n", "1", "alice", password, "2", "UAH", "1", "2", "USD", "1", "9", ""));
        String secondRun = runMain(stateFile, logFile,
                String.join("\n", "2", "bob", password, password, "2", "EUR", "1", "9", ""));

        assertFalse(firstRun.contains("Account number:"));
        assertFalse(secondRun.contains("Account number:"));
        String aliceFirstNewNumber = "UA000000000000000043";
        String aliceSecondNewNumber = "UA000000000000000044";
        String bobNumber = "UA000000000000000045";
        assertTrue(firstRun.contains("Account " + aliceFirstNewNumber + " (CHECKING) was added to alice."));
        assertTrue(firstRun.contains("Account " + aliceSecondNewNumber + " (CHECKING) was added to alice."));
        assertTrue(secondRun.contains("Account " + bobNumber + " (CHECKING) was added to bob."));
        Map<String, User> loaded = storage.loadUsers();
        assertEquals(List.of(highestNumber, legacyNumber, aliceFirstNewNumber, aliceSecondNewNumber),
                loaded.get("alice").getAccounts().stream().map(BankAccount::getAccountNumber).toList());
        assertEquals(List.of(bobNumber),
                loaded.get("bob").getAccounts().stream().map(BankAccount::getAccountNumber).toList());
    }

    @Test
    void shouldReportDuplicateAccountNumbersAndPreserveTheStateFile() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        String number = "legacy-001";
        User alice = new User("alice", "hash");
        User bob = new User("bob", "hash");
        alice.addAccount(new CheckingAccount(number, "alice", Currency.UAH));
        bob.addAccount(new CheckingAccount(number, "bob", Currency.USD));
        new JsonPersistence(stateFile).saveUsers(Map.of("alice", alice, "bob", bob));
        String original = Files.readString(stateFile);

        String console = runMain(stateFile, logFile, "");

        assertTrue(console.contains("Duplicate account number '" + number + "'"), console);
        assertTrue(console.contains("alice"), console);
        assertTrue(console.contains("bob"), console);
        assertFalse(console.contains("Choose an option:"), console);
        assertEquals(original, Files.readString(stateFile));
    }

    @Test
    void shouldOpenCreditAndTermDepositAccountsFromTheMenu() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        String password = "private-password";
        String input = String.join("\n",
                "2", "alice", password, password,
                "2", "USD", "2", "1000.00", "12",
                "2", "EUR", "3", "2030-06-01", "0.05", "y",
                "1", "9", "");

        String console = runMain(stateFile, logFile, input);

        assertTrue(console.contains(FIRST_ACCOUNT_NUMBER + " | CREDIT | 0 USD | ACTIVE | 0 transaction(s)"), console);
        assertTrue(console.contains("UA000000000000000002 | DEPOSIT | 0 EUR | ACTIVE | 0 transaction(s)"), console);
        assertTrue(console.contains("Account " + FIRST_ACCOUNT_NUMBER + " (CREDIT) was added to alice."));
        assertFalse(console.contains("Could not open the account"), console);

        List<JsonNode> events = readAudit(auditFile);
        assertEquals(8, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(1), "customer-001", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(2), "alice", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(3), "alice", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(4), "alice", "OPEN_ACCOUNT", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(5), "alice", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(6), "alice", "OPEN_ACCOUNT", "UA000000000000000002", "SUCCESS");
        assertEvent(events.get(7), "alice", "SAVE_STATE", null, "SUCCESS");
    }

    @Test
    void shouldRejectInvalidAccountParametersAndKeepAccountListUnchanged() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        String password = "private-password";
        String input = String.join("\n",
                "2", "alice", password, password,
                "2", "USD", "2", "-100.00", "12",
                "2", "EUR", "3", "2030-06-01", "0.05", "x",
                "1", "9", "");

        String console = runMain(stateFile, logFile, input);

        assertTrue(console.contains("Could not open the account: Credit limit cannot be negative"), console);
        assertTrue(console.contains("Expected 'y' or 'n', got 'x'"), console);
        assertTrue(console.contains("User 'alice' has no accounts yet."), console);

        List<JsonNode> events = readAudit(auditFile);
        assertEquals(6, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(1), "customer-001", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(2), "alice", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(3), "alice", "SAVE_STATE", null, "SUCCESS");
        assertEvent(events.get(4), "alice", "OPEN_ACCOUNT", null, "FAILURE");
        assertEvent(events.get(5), "alice", "OPEN_ACCOUNT", null, "FAILURE");
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
}
