package com.wealthview.api.controller;

import com.wealthview.core.price.PriceService;
import com.wealthview.core.price.dto.PriceRequest;
import com.wealthview.core.price.dto.PriceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/prices")
public class PriceController {

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping
    public List<PriceResponse> listLatestPrices() {
        return priceService.listLatestPrices();
    }

    @PostMapping
    public ResponseEntity<PriceResponse> create(@Valid @RequestBody PriceRequest request) {
        var response = priceService.createPrice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{symbol}/latest")
    public ResponseEntity<PriceResponse> getLatest(@PathVariable String symbol) {
        var response = priceService.getLatestPrice(symbol);
        return ResponseEntity.ok(response);
    }
}
