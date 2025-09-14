package com.aarahman;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * CommonUtil provides utility methods for common operations used across the application.
 * This class contains static helper methods for null-safe operations, system information,
 * command execution, and data type conversions.
 *
 * <p>Key functionalities:
 * <ul>
 *   <li>Null-safe value operations</li>
 *   <li>Safe evaluation with exception handling</li>
 *   <li>String comparison utilities</li>
 *   <li>Operating system and architecture detection</li>
 *   <li>Command execution with proper logging</li>
 *   <li>Date/time conversion utilities</li>
 * </ul>
 *
 * @author Aarahman
 * @version 1.0
 */
@Slf4j
public class CommonUtil {

    /**
     * Returns the first value if it's not null, otherwise returns the default value.
     * This is a null-safe alternative to direct value access.
     *
     * @param <T> The type of the values
     * @param value The value to check for null
     * @param returnThisIfValueIsNull The default value to return if the first value is null
     * @return The first value if not null, otherwise the default value
     */
    public static <T> T nvl(T value, T returnThisIfValueIsNull) {
        return value == null ? returnThisIfValueIsNull : value;
    }

    /**
     * Safely evaluates a supplier function, returning null if any exception occurs.
     * This method provides a way to execute potentially failing operations without
     * explicit exception handling at the call site.
     *
     * @param <T> The return type of the supplier
     * @param supplier The supplier function to evaluate
     * @return The result of the supplier, or null if an exception occurred
     */
    public static <T> T safeEval(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.debug("Safe evaluation failed", e);
            return null;
        }
    }

    /**
     * Checks if two strings contain each other (case-insensitive, trimmed comparison).
     * Returns true if either string contains the other, or if both are null.
     *
     * @param str1 First string to compare
     * @param str2 Second string to compare
     * @return true if strings contain each other or both are null, false otherwise
     */
    public static boolean contains(String str1, String str2) {
        if (str1 == null && str2 == null) {
            return true;
        }
        if (str1 != null && str2 != null) {
            if (str1.toLowerCase().trim().contains(str2.toLowerCase().trim())
                    || str2.toLowerCase().trim().contains(str1.toLowerCase().trim())) {
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * Determines if the current operating system is Windows.
     *
     * @return true if running on Windows, false otherwise
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }

    /**
     * Determines if the current processor architecture is ARM-based.
     * Checks for both "arm64" and "aarch64" architecture identifiers.
     *
     * @return true if running on ARM architecture, false otherwise
     */
    public static boolean isArmProcessor() {
        return Stream.of("arm64", "aarch64").anyMatch(s -> System.getProperty("os.arch").contains(s));
    }

    /**
     * Executes a system command and returns the combined output from both stdout and stderr.
     * The command execution has a timeout of 2 minutes to prevent hanging processes.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Logs the command being executed for debugging purposes</li>
     *   <li>Waits up to 2 minutes for command completion</li>
     *   <li>Captures both error stream and input stream output</li>
     *   <li>Logs the complete response for troubleshooting</li>
     *   <li>Handles exceptions gracefully with proper logging</li>
     * </ul>
     *
     * @param command The system command to execute
     * @return List of strings containing the command output lines
     * @throws RuntimeException if command execution fails critically
     */
    public static List<String> executeCommand(String command) throws RuntimeException {
        List<String> commandResponses = new ArrayList<>();
        try {
            log.info("Executing command: {}", command);
            Runtime rt = Runtime.getRuntime();
            Process proc = rt.exec(command);
            proc.waitFor(2, TimeUnit.MINUTES);
            commandResponses.addAll(getCommandResponse(proc.getErrorStream()));
            commandResponses.addAll(getCommandResponse(proc.getInputStream()));
            log.info("Command response: {}", commandResponses);
        } catch (Exception ex) {
            log.error("Failed to execute command: {}", command, ex);
        }
        return commandResponses;
    }

    /**
     * Reads all lines from an InputStream and returns them as a list of strings.
     * This method is used internally by executeCommand to capture process output.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses BufferedReader for efficient line-by-line reading</li>
     *   <li>Continues reading until no more lines are available</li>
     *   <li>Handles IO exceptions gracefully with proper logging</li>
     *   <li>Returns empty list if reading fails</li>
     * </ul>
     *
     * @param inputStream The InputStream to read from (typically process stdout or stderr)
     * @return List of strings, each representing a line from the input stream
     */
    private static List<String> getCommandResponse(InputStream inputStream) {
        List<String> commandResponses = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String commandResponse = br.readLine();
            while (commandResponse != null) {
                commandResponses.add(commandResponse);
                commandResponse = br.readLine();
            }
        } catch (Exception e) {
            log.error("Failed to read command response from input stream", e);
        }
        return commandResponses;
    }

    /**
     * Converts epoch milliseconds to ZonedDateTime using the system default timezone.
     * Returns null if the conversion fails for any reason.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses system default timezone for conversion</li>
     *   <li>Handles null input gracefully</li>
     *   <li>Returns null if conversion fails (e.g., invalid epoch value)</li>
     *   <li>Silently handles exceptions to avoid disrupting calling code</li>
     * </ul>
     *
     * @param epochMillis The epoch time in milliseconds to convert
     * @return ZonedDateTime representation, or null if conversion fails
     */
    public static ZonedDateTime convertEpochToZonedDateTime(Long epochMillis) {
        ZonedDateTime zdt = null;
        try {
            if (epochMillis != null) {
                zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
            }
        } catch (Exception ex) {
            log.debug("Failed to convert epoch {} to ZonedDateTime", epochMillis, ex);
        }
        return zdt;
    }
}
