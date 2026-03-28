package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.core.exchangerate.dto.ExchangeRateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/exchange-rates")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @GetMapping
    public List<ExchangeRateResponse> list(@AuthenticationPrincipal TenantUserPrincipal principal) {
        return exchangeRateService.list(principal.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExchangeRateResponse create(@AuthenticationPrincipal TenantUserPrincipal principal,
                                       @Valid @RequestBody ExchangeRateRequest request) {
        return exchangeRateService.create(principal.tenantId(), request);
    }

    @PutMapping("/{currencyCode}")
    public ExchangeRateResponse update(@AuthenticationPrincipal TenantUserPrincipal principal,
                                       @PathVariable String currencyCode,
                                       @Valid @RequestBody ExchangeRateRequest request) {
        return exchangeRateService.update(principal.tenantId(), currencyCode, request.rateToUsd());
    }

    @DeleteMapping("/{currencyCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TenantUserPrincipal principal,
                       @PathVariable String currencyCode) {
        exchangeRateService.delete(principal.tenantId(), currencyCode);
    }
}
