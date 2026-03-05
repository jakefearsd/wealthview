package com.wealthview.core.price;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.price.dto.PriceRequest;
import com.wealthview.core.price.dto.PriceResponse;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.repository.PriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class PriceService {

    private final PriceRepository priceRepository;

    public PriceService(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    @Transactional
    public PriceResponse createPrice(PriceRequest request) {
        var price = new PriceEntity(request.symbol(), request.date(),
                request.closePrice(), "manual");
        price = priceRepository.save(price);
        return PriceResponse.from(price);
    }

    @Transactional(readOnly = true)
    public PriceResponse getLatestPrice(String symbol) {
        return priceRepository.findFirstBySymbolOrderByDateDesc(symbol)
                .map(PriceResponse::from)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No price found for symbol: " + symbol));
    }

    @Transactional(readOnly = true)
    public Optional<PriceResponse> findLatestPrice(String symbol) {
        return priceRepository.findFirstBySymbolOrderByDateDesc(symbol)
                .map(PriceResponse::from);
    }
}
