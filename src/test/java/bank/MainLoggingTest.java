package bank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainLoggingTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLogConsoleSessionInUtf8AndAppendAfterRestart() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        Path logFile = tempDir.resolve("logs/bank.log");
        String password = "private-test-password";
        String input = String.join("\n", "unknown", "2", "клієнт", password, password,
                "2", "UA001", "UAH", "5", "UA001", "125.50", "1", "6", "");

        String console = runMain(stateFile, logFile, input);
        String firstLog = Files.readString(logFile);

        assertTrue(console.contains("Choose an option:"));
        assertTrue(console.contains("Password:"));
        assertTrue(console.contains("[WARNING] Unknown option"));
        assertTrue(console.contains("Main menu"));
        assertTrue(console.contains("Welcome, клієнт!"));
        assertFalse(console.contains("bank.Main"), "Console should omit class and method names");
        assertFalse(console.contains("INFO:"), "Menu should omit log metadata");
        assertTrue(firstLog.contains("WARNING: Unknown option"));
        assertTrue(firstLog.contains("INFO: Welcome, клієнт!"));
        assertTrue(firstLog.contains("UA001 | CHECKING | 125.50 UAH | ACTIVE | 1 transaction(s)"));
        assertTrue(firstLog.contains("Bank state saved"), firstLog);
        assertFalse(firstLog.contains(password));
        assertFalse(firstLog.contains("secret123"));

        runMain(stateFile, logFile, "3\n");

        String secondLog = Files.readString(logFile);
        assertTrue(secondLog.startsWith(firstLog), "Restart must append to the existing log");
        assertTrue(secondLog.length() > firstLog.length());
        assertTrue(secondLog.contains("Loaded 2 user(s)"));
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
        assertTrue(log.contains("SEVERE: Could not load the bank state"));
        assertTrue(log.contains("bank.storage.StorageException"));
        assertTrue(log.contains("at bank.storage.JsonPersistence.loadUsers"));
    }

    private String runMain(Path stateFile, Path logFile, String input) throws Exception {
        Path consoleFile = Files.createTempFile(tempDir, "console-", ".txt");
        Path errorFile = Files.createTempFile(tempDir, "stderr-", ".txt");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process = new ProcessBuilder(java.toString(),
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-Dbank.log.file=" + logFile,
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
