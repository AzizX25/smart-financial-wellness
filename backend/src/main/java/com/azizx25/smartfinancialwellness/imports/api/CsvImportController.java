package com.azizx25.smartfinancialwellness.imports.api;

import java.util.UUID;

import com.azizx25.smartfinancialwellness.common.web.ApiError;
import com.azizx25.smartfinancialwellness.imports.CsvImportService;
import com.azizx25.smartfinancialwellness.imports.api.CsvImportDtos.*;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
@RequestMapping("/api/imports")
public class CsvImportController {
    private final CsvImportService service;
    public CsvImportController(CsvImportService service) { this.service = service; }

    @PostMapping("/accounts")
    public ImportAccountResponse account(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @Valid @RequestBody AccountRequest request) {
        return service.createAccount(principal.userId(), request);
    }

    @PostMapping(value = "/csv/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewResponse preview(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestPart("file") MultipartFile file, @Valid @RequestPart("request") ImportRequest request) {
        return service.preview(principal.userId(), file, request);
    }

    @PostMapping(value = "/csv/{jobId}/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponse confirm(@AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID jobId, @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("request") ImportRequest request) {
        return service.confirm(principal.userId(), jobId, file, request);
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiError> malformedUpload(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST,
                "INVALID_CSV_UPLOAD", "Sélectionnez un fichier CSV de 2 Mo au maximum et un mapping valide.", request.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> oversizedUpload(HttpServletRequest request) {
        return ResponseEntity.status(413).body(ApiError.of(HttpStatus.valueOf(413),
                "CSV_UPLOAD_TOO_LARGE", "Limite du fichier : 2 Mo.", request.getRequestURI()));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, org.hibernate.exception.ConstraintViolationException.class})
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(HttpStatus.CONFLICT,
                "CSV_IMPORT_CONFLICT", "L'import a changé. Prévisualisez à nouveau le fichier.", request.getRequestURI()));
    }
}
