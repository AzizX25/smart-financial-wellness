package com.azizx25.smartfinancialwellness.assistance;

import java.io.IOException;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import com.azizx25.smartfinancialwellness.common.web.ApiError;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
public class AssistanceController {
    private final AssistanceService service;
    public AssistanceController(AssistanceService service) { this.service = service; }

    @PostMapping("/api/recommendations/{id}/explanation")
    public AssistanceService.Explanation explain(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID id) { return service.explain(principal.userId(), id); }

    @PostMapping(value = "/api/receipts/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssistanceService.ReceiptPreview receipt(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestPart MultipartFile file) {
        try { return service.receipt(principal.userId(), file.getBytes()); }
        catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECEIPT_IMAGE", "Receipt image could not be read");
        }
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiError> missingImage(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST,
                "INVALID_RECEIPT_IMAGE", "One valid receipt image is required", request.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> oversizedImage(HttpServletRequest request) {
        return ResponseEntity.status(413).body(ApiError.of(HttpStatus.valueOf(413),
                "INVALID_RECEIPT_IMAGE", "Receipt image is too large", request.getRequestURI()));
    }
}
