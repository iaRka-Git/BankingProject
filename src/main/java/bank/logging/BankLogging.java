package bank.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/** Configures console and UTF-8 file logging for the bank application. */
public final class BankLogging {

    private static final Logger APPLICATION_LOGGER = Logger.getLogger("bank");
    private static final Logger CONSOLE_LOGGER = Logger.getLogger("bank.console");

    static {
        configure();
    }

    private BankLogging() {
    }

    /** Returns a technical logger writing to the general log file and console. */
    public static Logger getLogger(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    /** Returns a UI logger whose messages are displayed in the console only. */
    public static Logger getConsoleLogger(Class<?> type) {
        return Logger.getLogger("bank.console." + type.getName());
    }

    private static void configure() {
        Path logFile = Path.of(System.getProperty("bank.log.file", "logs/bank.log"))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(logFile.getParent());

            FileHandler fileHandler = new FileHandler(logFile.toString(), true);
            fileHandler.setEncoding(StandardCharsets.UTF_8.name());
            fileHandler.setFormatter(new FileFormatter());
            fileHandler.setLevel(Level.ALL);

            StreamHandler consoleHandler = new StdoutHandler();
            consoleHandler.setEncoding(StandardCharsets.UTF_8.name());
            consoleHandler.setLevel(Level.ALL);

            APPLICATION_LOGGER.setUseParentHandlers(false);
            APPLICATION_LOGGER.setLevel(Level.ALL);
            APPLICATION_LOGGER.addHandler(fileHandler);
            APPLICATION_LOGGER.addHandler(consoleHandler);

            CONSOLE_LOGGER.setUseParentHandlers(false);
            CONSOLE_LOGGER.setLevel(Level.ALL);
            CONSOLE_LOGGER.addHandler(consoleHandler);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialize logging to '" + logFile + "'", e);
        }
    }

    /** Uses one readable header per event without compiler-generated method names. */
    private static final class FileFormatter extends Formatter {

        private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
                .ofPattern("uuuu-MM-dd HH:mm:ss XXX")
                .withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            String header = "%s [%s] %s - %s%n".formatted(
                    TIMESTAMP_FORMAT.format(record.getInstant()),
                    record.getLevel().getName(), record.getLoggerName(), formatMessage(record));
            if (record.getThrown() == null) {
                return header;
            }
            StringWriter stackTrace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(stackTrace));
            return header + stackTrace;
        }
    }

    /** Flushes each message so menu prompts are visible before input is read. */
    private static final class StdoutHandler extends StreamHandler {

        private StdoutHandler() {
            super(System.out, new ConsoleFormatter());
        }

        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }

        @Override
        public synchronized void close() {
            // The application owns stdout; logging must not close it.
            flush();
        }
    }

    /** Keeps console messages compact; full diagnostic details go to the file. */
    private static final class ConsoleFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            String prefix = record.getLevel() == Level.INFO
                    ? "" : "[" + record.getLevel().getName() + "] ";
            return prefix + formatMessage(record) + System.lineSeparator();
        }
    }
}
