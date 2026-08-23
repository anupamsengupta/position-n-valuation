package com.power.posval.domain.exception;

/**
 * Thrown by AuctionResultParser implementations when a CSV feed file cannot be parsed.
 * Defined in pv-domain because it is part of the AuctionResultParser port contract:
 * callers of the port must be able to catch this exception without depending on
 * a specific adapter module.
 * {@code lineNumber} is 1-based; 0 means the error is file-level (e.g. encoding, header).
 * {@code detail} carries the raw CSV fragment or a parser diagnostic message.
 * S5.1, DA-VOL-01.
 */
public class CsvParseException extends RuntimeException {

    private final int lineNumber;
    private final String detail;

    public CsvParseException(String message, int lineNumber, String detail) {
        super(message);
        this.lineNumber = lineNumber;
        this.detail = detail;
    }

    public CsvParseException(String message, int lineNumber, String detail, Throwable cause) {
        super(message, cause);
        this.lineNumber = lineNumber;
        this.detail = detail;
    }

    /** 1-based line number within the CSV file where the error was detected. 0 = file-level. */
    public int lineNumber() { return lineNumber; }

    /** Raw CSV fragment or parser diagnostic that caused the error. */
    public String detail() { return detail; }
}
