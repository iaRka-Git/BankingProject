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
import java.math.BigDecimal;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCliOperationsTest {

    private static final String FIRST_ACCOUNT_NUMBER = "UA000000000000000001";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldWithdrawFromOwnAccountAndDisplayStatement() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        String password = "private-password";
        String input = String.join("\n",
                "2", "alice", password, password,
                "2", "UAH", "1",
                "8", FIRST_ACCOUNT_NUMBER,
                "5", FIRST_ACCOUNT_NUMBER, "100.00",
                "6", FIRST_ACCOUNT_NUMBER, "35.25",
                "8", FIRST_ACCOUNT_NUMBER,
                "9", "");

        String console = runMain(stateFile, logFile, input);

        assertTrue(console.contains("Account " + FIRST_ACCOUNT_NUMBER + " (0 UAH) has no transactions yet."), console);
        assertTrue(console.contains("New balance of " + FIRST_ACCOUNT_NUMBER + ": 64.75 UAH"), console);
        assertTrue(console.contains("Statement for " + FIRST_ACCOUNT_NUMBER + " (balance: 64.75 UAH):"), console);
        assertTrue(Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\| \\+100\\.00 UAH \\| Console deposit")
                .matcher(console).find(), console);
        assertTrue(Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\| -35\\.25 UAH \\| Console withdrawal")
                .matcher(console).find(), console);

        BankAccount reloaded = new JsonPersistence(stateFile).loadUsers()
                .get("alice").findAccount(FIRST_ACCOUNT_NUMBER).orElseThrow();
        assertEquals(0, new BigDecimal("64.75").compareTo(reloaded.getBalance()));
        assertEquals(2, reloaded.getStatement().size());

        String auditText = Files.readString(auditFile);
        List<JsonNode> events = readAudit(auditFile);
        assertEquals(6, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(1), "alice", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(2), "alice", "OPEN_ACCOUNT", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(3), "alice", "DEPOSIT", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(4), "alice", "WITHDRAW", FIRST_ACCOUNT_NUMBER, "SUCCESS");
        assertEvent(events.get(5), "alice", "SAVE_STATE", null, "SUCCESS");
        assertFalse(auditText.contains("35.25"));
        assertFalse(auditText.contains("64.75"));
        assertFalse(auditText.contains("100.00"));

        String technicalLog = Files.readString(logFile);
        assertFalse(technicalLog.contains("alice"));
        assertFalse(technicalLog.contains(FIRST_ACCOUNT_NUMBER));
        assertFalse(technicalLog.contains("35.25"));
        assertFalse(technicalLog.contains("64.75"));
        assertFalse(technicalLog.contains("100.00"));
    }

    @Test
    void shouldTransferFundsBetweenUsersAndRecordBothStatements() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        String password = "private-password";
        String secondAccountNumber = "UA000000000000000002";

        runMain(stateFile, logFile, String.join("\n",
                "2", "alice", password, password,
                "2", "UAH", "1",
                "5", FIRST_ACCOUNT_NUMBER, "200.00",
                "9", ""));
        runMain(stateFile, logFile, String.join("\n",
                "2", "bob", password, password,
                "2", "UAH", "1",
                "9", ""));
        String aliceConsole = runMain(stateFile, logFile, String.join("\n",
                "1", "alice", password,
                "7", FIRST_ACCOUNT_NUMBER, secondAccountNumber, "75.50",
                "8", FIRST_ACCOUNT_NUMBER,
                "9", ""));
        String bobConsole = runMain(stateFile, logFile, String.join("\n",
                "1", "bob", password,
                "8", secondAccountNumber,
                "9", ""));

        assertTrue(aliceConsole.contains("Transferred 75.50 UAH from " + FIRST_ACCOUNT_NUMBER
                + " to " + secondAccountNumber + ". New balance of " + FIRST_ACCOUNT_NUMBER + ": 124.50 UAH"),
                aliceConsole);
        assertTrue(aliceConsole.contains("Statement for " + FIRST_ACCOUNT_NUMBER + " (balance: 124.50 UAH):"),
                aliceConsole);
        assertTrue(Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\| -75\\.50 UAH \\| Transfer to "
                + secondAccountNumber).matcher(aliceConsole).find(), aliceConsole);

        assertTrue(bobConsole.contains("Statement for " + secondAccountNumber + " (balance: 75.50 UAH):"),
                bobConsole);
        assertTrue(Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\| \\+75\\.50 UAH \\| Transfer from "
                + FIRST_ACCOUNT_NUMBER).matcher(bobConsole).find(), bobConsole);

        Map<String, User> reloaded = new JsonPersistence(stateFile).loadUsers();
        BankAccount aliceAccount = reloaded.get("alice").findAccount(FIRST_ACCOUNT_NUMBER).orElseThrow();
        BankAccount bobAccount = reloaded.get("bob").findAccount(secondAccountNumber).orElseThrow();
        assertEquals(0, new BigDecimal("124.50").compareTo(aliceAccount.getBalance()));
        assertEquals(0, new BigDecimal("75.50").compareTo(bobAccount.getBalance()));
        assertEquals(2, aliceAccount.getStatement().size());
        assertEquals(1, bobAccount.getStatement().size());

        List<JsonNode> events = readAudit(auditFile);
        assertTrue(events.stream().anyMatch(e ->
                "alice".equals(e.get("actor").asText())
                        && "TRANSFER".equals(e.get("action").asText())
                        && (FIRST_ACCOUNT_NUMBER + "->" + secondAccountNumber).equals(e.get("target").asText())
                        && "SUCCESS".equals(e.get("status").asText())));
        String auditText = Files.readString(auditFile);
        assertFalse(auditText.contains("75.50"));
        assertFalse(auditText.contains("124.50"));
        String technicalLog = Files.readString(logFile);
        assertFalse(technicalLog.contains("alice"));
        assertFalse(technicalLog.contains("bob"));
        assertFalse(technicalLog.contains("75.50"));
    }

    @Test
    void shouldForbidDebitingOrViewingStatementOfAnotherUsersAccount() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        String password = "private-password";
        String bobAccountNumber = "UA000000000000000002";

        User alice = new User("alice", AuthService.hashPassword(password));
        alice.addAccount(newFundedCheckingAccount(FIRST_ACCOUNT_NUMBER, "alice", Currency.UAH,
                "100.00", "Private salary", true));
        User bob = new User("bob", AuthService.hashPassword(password));
        bob.addAccount(newFundedCheckingAccount(bobAccountNumber, "bob", Currency.UAH,
                "50.00", "Initial", true));
        JsonPersistence storage = new JsonPersistence(stateFile);
        storage.saveUsers(Map.of("alice", alice, "bob", bob));

        String console = runMain(stateFile, logFile, String.join("\n",
                "1", "bob", password,
                "6", FIRST_ACCOUNT_NUMBER, "25.00",
                "7", FIRST_ACCOUNT_NUMBER, bobAccountNumber, "25.00",
                "8", FIRST_ACCOUNT_NUMBER,
                "9", ""));

        assertTrue(console.contains("User 'bob' has no account with number '" + FIRST_ACCOUNT_NUMBER + "'."), console);
        assertFalse(console.contains("Private salary"), console);
        assertFalse(console.contains("+100.00 UAH"), console);

        Map<String, User> reloaded = storage.loadUsers();
        BankAccount reloadedAlice = reloaded.get("alice").findAccount(FIRST_ACCOUNT_NUMBER).orElseThrow();
        BankAccount reloadedBob = reloaded.get("bob").findAccount(bobAccountNumber).orElseThrow();
        assertEquals(0, new BigDecimal("100.00").compareTo(reloadedAlice.getBalance()));
        assertEquals(1, reloadedAlice.getStatement().size());
        assertEquals(0, new BigDecimal("50.00").compareTo(reloadedBob.getBalance()));
        assertEquals(1, reloadedBob.getStatement().size());

        List<JsonNode> events = readAudit(auditFile);
        assertEquals(4, events.size());
        assertEvent(events.get(0), "bob", "LOGIN", null, "SUCCESS");
        assertEvent(events.get(1), "bob", "WITHDRAW", FIRST_ACCOUNT_NUMBER, "FAILURE");
        assertEvent(events.get(2), "bob", "TRANSFER", FIRST_ACCOUNT_NUMBER + "->" + bobAccountNumber, "FAILURE");
        assertEvent(events.get(3), "bob", "SAVE_STATE", null, "SUCCESS");
    }

    @Test
    void shouldRejectInvalidWithdrawalsAndTransfersWithoutMutatingState() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        Path auditFile = logFile.resolveSibling("security-audit.jsonl");
        String password = "private-password";

        String aliceActive = FIRST_ACCOUNT_NUMBER;
        String aliceBlocked = "UA000000000000000002";
        String bobBlocked = "UA000000000000000003";
        String bobActiveUah = "UA000000000000000004";
        String bobActiveUsd = "UA000000000000000005";
        String unknownAccount = "UA999999999999999999";

        User alice = new User("alice", AuthService.hashPassword(password));
        alice.addAccount(newFundedCheckingAccount(aliceActive, "alice", Currency.UAH, "100.00", "Seed", true));
        alice.addAccount(newFundedCheckingAccount(aliceBlocked, "alice", Currency.UAH, "50.00", "Seed", false));

        User bob = new User("bob", AuthService.hashPassword(password));
        bob.addAccount(newFundedCheckingAccount(bobBlocked, "bob", Currency.UAH, "20.00", "Seed", false));
        bob.addAccount(newFundedCheckingAccount(bobActiveUah, "bob", Currency.UAH, "10.00", "Seed", true));
        bob.addAccount(newFundedCheckingAccount(bobActiveUsd, "bob", Currency.USD, "10.00", "Seed", true));

        JsonPersistence storage = new JsonPersistence(stateFile);
        storage.saveUsers(Map.of("alice", alice, "bob", bob));

        String console = runMain(stateFile, logFile, String.join("\n",
                "1", "alice", password,
                "6", aliceActive, "250.00",
                "6", aliceBlocked, "10.00",
                "6", aliceActive, "abc",
                "7", aliceActive, unknownAccount, "10.00",
                "7", aliceActive, bobBlocked, "10.00",
                "7", aliceBlocked, bobActiveUah, "10.00",
                "7", aliceActive, bobActiveUah, "250.00",
                "7", aliceActive, bobActiveUsd, "10.00",
                "9", ""));

        assertTrue(console.contains("Withdrawal failed: Insufficient funds"), console);
        assertTrue(console.contains("Withdrawal failed: Account is blocked or inactive."), console);
        assertTrue(console.contains("'abc' is not a valid amount."), console);
        assertTrue(console.contains("Recipient account '" + unknownAccount + "' was not found."), console);
        assertTrue(console.contains("Transfer failed: Target account is blocked or inactive."), console);
        assertTrue(console.contains("Transfer failed: Account is blocked or inactive."), console);
        assertTrue(console.contains("Transfer failed: Insufficient funds"), console);
        assertTrue(console.contains("Transfer failed: Cross-currency transfers are not supported yet."), console);

        Map<String, User> reloaded = storage.loadUsers();
        assertAccountState(reloaded.get("alice"), aliceActive, "100.00", 1);
        assertAccountState(reloaded.get("alice"), aliceBlocked, "50.00", 1);
        assertAccountState(reloaded.get("bob"), bobBlocked, "20.00", 1);
        assertAccountState(reloaded.get("bob"), bobActiveUah, "10.00", 1);
        assertAccountState(reloaded.get("bob"), bobActiveUsd, "10.00", 1);

        List<JsonNode> events = readAudit(auditFile);
        assertEquals(10, events.size());
        assertEvent(events.get(0), "alice", "LOGIN", null, "SUCCESS");
        assertEvent(events.get(1), "alice", "WITHDRAW", aliceActive, "FAILURE");
        assertEvent(events.get(2), "alice", "WITHDRAW", aliceBlocked, "FAILURE");
        assertEvent(events.get(3), "alice", "WITHDRAW", aliceActive, "FAILURE");
        assertEvent(events.get(4), "alice", "TRANSFER", aliceActive + "->" + unknownAccount, "FAILURE");
        assertEvent(events.get(5), "alice", "TRANSFER", aliceActive + "->" + bobBlocked, "FAILURE");
        assertEvent(events.get(6), "alice", "TRANSFER", aliceBlocked + "->" + bobActiveUah, "FAILURE");
        assertEvent(events.get(7), "alice", "TRANSFER", aliceActive + "->" + bobActiveUah, "FAILURE");
        assertEvent(events.get(8), "alice", "TRANSFER", aliceActive + "->" + bobActiveUsd, "FAILURE");
        assertEvent(events.get(9), "alice", "SAVE_STATE", null, "SUCCESS");
    }

    private static CheckingAccount newFundedCheckingAccount(String accountNumber, String ownerId, Currency currency,
                                                            String amount, String comment, boolean active) {
        CheckingAccount account = new CheckingAccount(accountNumber, ownerId, currency);
        account.deposit(new BigDecimal(amount), comment);
        account.setActive(active);
        return account;
    }

    private static void assertAccountState(User owner, String accountNumber, String expectedBalance,
                                           int expectedStatements) {
        BankAccount account = owner.findAccount(accountNumber).orElseThrow();
        assertEquals(0, new BigDecimal(expectedBalance).compareTo(account.getBalance()));
        assertEquals(expectedStatements, account.getStatement().size());
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
