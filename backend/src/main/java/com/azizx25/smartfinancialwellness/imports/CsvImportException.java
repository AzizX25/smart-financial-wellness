package com.azizx25.smartfinancialwellness.imports;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class CsvImportException extends ApiException {
    public CsvImportException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static CsvImportException invalid(String message) {
        return new CsvImportException(HttpStatus.BAD_REQUEST, "INVALID_CSV_IMPORT", message);
    }

    public static CsvImportException notFound() {
        return new CsvImportException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Import or account not found");
    }

    public static CsvImportException conflict(String message) {
        return new CsvImportException(HttpStatus.CONFLICT, "CSV_IMPORT_CONFLICT", message);
    }
}
