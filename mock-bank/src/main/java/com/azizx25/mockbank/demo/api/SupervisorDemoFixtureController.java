package com.azizx25.mockbank.demo.api;

import com.azizx25.mockbank.demo.SupervisorDemoFixtureService;
import com.azizx25.mockbank.demo.api.SupervisorDemoDtos.ResetRequest;
import com.azizx25.mockbank.demo.api.SupervisorDemoDtos.ResetResponse;
import com.azizx25.mockbank.demo.api.SupervisorDemoDtos.SeedRequest;
import com.azizx25.mockbank.demo.api.SupervisorDemoDtos.SeedResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/demo-fixtures/supervisor")
@Profile("demo")
@ConditionalOnProperty(name = "app.demo-fixtures.enabled", havingValue = "true")
public class SupervisorDemoFixtureController {

    private final SupervisorDemoFixtureService fixtureService;

    public SupervisorDemoFixtureController(SupervisorDemoFixtureService fixtureService) {
        this.fixtureService = fixtureService;
    }

    @PostMapping
    public SeedResponse seed(@Valid @RequestBody SeedRequest request) {
        return fixtureService.seed(
                request.scenarioVersion(), request.asOfDate(), request.customerPassword());
    }

    @PostMapping("/reset")
    public ResetResponse reset(@Valid @RequestBody ResetRequest request) {
        return fixtureService.reset(
                request.scenarioVersion(), request.username(), request.confirmation());
    }
}
