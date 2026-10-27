package bank;

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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainLoggingTest {

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
                "2", "UA001", "UAH", "5", "UA001", "125.50",
                "3", "UA001", "5", "UA001", "999.99", "4", "UA001", "1", "6", "");

        String console = runMain(stateFile, logFile, input);
        String firstLog = Files.readString(logFile);

        assertTrue(console.contains("Choose an option:"));
        assertTrue(console.contains("Password:"));
        assertTrue(console.contains("[WARNING] Unknown option"));
        assertTrue(console.contains("Main menu"));
        assertTrue(console.contains("Welcome, клієнт!"));
        assertFalse(console.contains("bank.Main"), "Console should omit class and method names");
        assertFalse(console.contains("INFO:"), "Menu should omit log metadata");
        assertTrue(console.contains("UA001 | CHECKING | 125.50 UAH | ACTIVE | 1 transaction(s)"));
        assertTrue(firstLog.contains("[INFO] bank.Main - Bank state saved"), firstLog);
        assertFalse(firstLog.contains("lambda$"), "Log headers should omit generated method names");
        assertFalse(firstLog.contains("Choose an option"));
        assertFalse(firstLog.contains("Password:"));
        assertFalse(firstLog.contains("Unknown option"));
        assertFalse(firstLog.contains("клієнт"));
        assertFalse(firstLog.contains("UA001"));
        assertFalse(firstLog.contains("125.50"));
        assertFalse(firstLog.contains("999.99"));
        assertFalse(firstLog.contains(password));
        assertFalse(firstLog.contains("secret123"));

        String firstAudit = Files.readString(auditFile);
        List<JsonNode> events = readAudit(auditFile);
        assertEquals(8, events.size());
        assertEvent(events.get(0), "customer-001", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(1), "клієнт", "REGISTER", null, "SUCCESS");
        assertEvent(events.get(2), "клієнт", "OPEN_ACCOUNT", "UA001", "SUCCESS");
        assertEvent(events.get(3), "клієнт", "DEPOSIT", "UA001", "SUCCESS");
        assertEvent(events.get(4), "клієнт", "BLOCK_ACCOUNT", "UA001", "SUCCESS");
        assertEvent(events.get(5), "клієнт", "DEPOSIT", "UA001", "FAILURE");
        assertEvent(events.get(6), "клієнт", "UNBLOCK_ACCOUNT", "UA001", "SUCCESS");
        assertEvent(events.get(7), "клієнт", "SAVE_STATE", null, "SUCCESS");
        assertFalse(firstAudit.contains(password));
        assertFalse(firstAudit.contains("secret123"));
        assertFalse(firstAudit.contains("125.50"));
        assertFalse(firstAudit.contains("999.99"));
        assertFalse(firstAudit.contains("Choose an option"));
        assertFalse(console.contains("\"action\""), "Audit records must not propagate to the console");

        String nextInput = String.join("\n", "2", "клієнт", password, password,
                "1", "клієнт", "wrong-password", "клієнт", password,
                "5", "UA404", "125.50", "6", "");
        runMain(stateFile, logFile, nextInput);

        String secondLog = Files.readString(logFile);
        assertTrue(secondLog.startsWith(firstLog), "Restart must append to the existing log");
        assertTrue(secondLog.length() > firstLog.length());
        assertFalse(secondLog.contains("клієнт"));
        assertFalse(secondLog.contains("Login failed"));
        assertFalse(secondLog.contains("wrong-password"));
        assertTrue(Files.readString(auditFile).startsWith(firstAudit));
        events = readAudit(auditFile);
        assertEquals(13, events.size());
        assertEvent(events.get(8), "клієнт", "REGISTER", null, "FAILURE");
        assertEvent(events.get(9), "клієнт", "LOGIN", null, "FAILURE");
        assertEvent(events.get(10), "клієнт", "LOGIN", null, "SUCCESS");
        assertEvent(events.get(11), "клієнт", "DEPOSIT", "UA404", "FAILURE");
        assertEvent(events.get(12), "клієнт", "SAVE_STATE", null, "SUCCESS");
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
                "1\ncustomer-001\nsecret123\n6\n");

        List<JsonNode> events = readAudit(logFile.resolveSibling("security-audit.jsonl"));
        assertEquals(3, events.size());
        assertEvent(events.getLast(), "customer-001", "SAVE_STATE", null, "FAILURE");
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
        assertEquals(before.size() + 1, after.size());
        assertEvent(after.getLast(), "system", "SAVE_STATE", null, "SUCCESS");
        assertFalse(Files.readString(logFile).contains("Unknown option"));
    }

    @Test
    void shouldEscapeIdentifiersInStructuredAuditRecords() throws Exception {
        Path logFile = tempDir.resolve("logs/bank.log");
        String actor = "user\"\\\tidentifier";
        String accountNumber = "UA\"\\\t001";
        String input = String.join("\n", "2", actor, "private-password", "private-password",
                "2", accountNumber, "UAH", "6", "");

        runMain(tempDir.resolve("state.json"), logFile, input);

        List<JsonNode> events = readAudit(logFile.resolveSibling("security-audit.jsonl"));
        assertEquals(4, events.size());
        assertEvent(events.get(1), actor, "REGISTER", null, "SUCCESS");
        assertEvent(events.get(2), actor, "OPEN_ACCOUNT", accountNumber, "SUCCESS");
        assertEvent(events.get(3), actor, "SAVE_STATE", null, "SUCCESS");
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
