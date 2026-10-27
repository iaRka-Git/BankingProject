package bank.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Writes security and banking operation outcomes to a dedicated UTF-8 JSON Lines file.
 * Each line contains only a UTC timestamp, actor identifier, action, optional target
 * identifier, and status. Passwords, amounts, balances, comments, and exception messages
 * must never be passed to this logger. Audit records do not propagate to other handlers.
 */
public final class SecurityAuditLogger {

    public enum Action {
        REGISTER, LOGIN, OPEN_ACCOUNT, BLOCK_ACCOUNT, UNBLOCK_ACCOUNT, DEPOSIT, SAVE_STATE
    }

    public enum Status {
        SUCCESS, FAILURE
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger AUDIT_LOGGER = createLogger();

    private SecurityAuditLogger() {
    }

    /**
     * Records one operation outcome. Missing actors are represented as {@code anonymous};
     * {@code target} may be null when the operation does not address a specific account.
     * For authentication attempts, actor is the claimed user identifier, not proof of login.
     * Identifiers are JSON-escaped so they cannot introduce additional audit records.
     */
    public static void record(String actor, Action action, String target, Status status) {
        Objects.requireNonNull(action, "Audit action cannot be null");
        Objects.requireNonNull(status, "Audit status cannot be null");
        String actorId = actor == null || actor.isBlank() ? "anonymous" : actor.trim();
        AuditEvent event = new AuditEvent(Instant.now().toString(), actorId, action, target, status);
        try {
            AUDIT_LOGGER.info(OBJECT_MAPPER.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize security audit event", e);
        }
    }

    private static Logger createLogger() {
        Path auditFile = Path.of(System.getProperty("bank.audit.file", "logs/security-audit.jsonl"))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(auditFile.getParent());
            FileHandler fileHandler = new FileHandler(auditFile.toString(), true);
            fileHandler.setEncoding(StandardCharsets.UTF_8.name());
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return record.getMessage() + System.lineSeparator();
                }
            });

            Logger logger = Logger.getLogger("bank.audit");
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.INFO);
            logger.addHandler(fileHandler);
            return logger;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialize security audit logging to '" + auditFile + "'", e);
        }
    }

    private record AuditEvent(String timestamp, String actor, Action action, String target, Status status) {
    }
}
