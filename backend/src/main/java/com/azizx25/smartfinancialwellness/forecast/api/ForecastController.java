package com.azizx25.smartfinancialwellness.forecast.api;

import java.util.UUID;

import com.azizx25.smartfinancialwellness.forecast.ForecastService;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastPageResponse;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastResponse;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastSimulationRequest;
import com.azizx25.smartfinancialwellness.forecast.api.ForecastDtos.ForecastSimulationResponse;
import com.azizx25.smartfinancialwellness.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/forecasts")
public class ForecastController {

    private static final String CURRENCY = "[A-Z]{3}";
    private final ForecastService service;

    public ForecastController(ForecastService service) {
        this.service = service;
    }

    @PostMapping("/current")
    public ForecastResponse current(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = CURRENCY) String currency,
            @RequestParam String timezone) {
        return service.generateCurrent(principal.userId(), currency, timezone);
    }

    @GetMapping
    public ForecastPageResponse list(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestParam @Pattern(regexp = CURRENCY) String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return service.list(principal.userId(), currency, page, size);
    }

    @GetMapping("/{forecastId}")
    public ForecastResponse get(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID forecastId) {
        return service.get(principal.userId(), forecastId);
    }

    @PostMapping("/{forecastId}/simulations")
    public ForecastSimulationResponse simulate(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable UUID forecastId,
            @Valid @RequestBody ForecastSimulationRequest request) {
        return service.simulate(principal.userId(), forecastId, request);
    }
}
