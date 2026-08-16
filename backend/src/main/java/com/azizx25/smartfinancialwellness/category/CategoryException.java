package com.azizx25.smartfinancialwellness.category;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class CategoryException extends ApiException {

    public CategoryException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static CategoryException notFound() {
        return new CategoryException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Category not found");
    }

    public static CategoryException duplicate() {
        return new CategoryException(
                HttpStatus.CONFLICT, "CATEGORY_NAME_ALREADY_EXISTS", "A category with this name already exists");
    }

    public static CategoryException inUse() {
        return new CategoryException(HttpStatus.CONFLICT, "CATEGORY_IN_USE", "Category is in use");
    }

    public static CategoryException readOnly() {
        return new CategoryException(HttpStatus.FORBIDDEN, "CATEGORY_READ_ONLY", "System categories are read-only");
    }

    public static CategoryException notAllowed() {
        return new CategoryException(
                HttpStatus.UNPROCESSABLE_CONTENT, "CATEGORY_NOT_ALLOWED", "Category is not allowed for this transaction");
    }

    public static CategoryException transactionNotFound() {
        return new CategoryException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found");
    }
}
