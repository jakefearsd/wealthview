package com.wealthview.core.price;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.price.dto.PriceRequest;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.repository.PriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private PriceService priceService;

    @Test
    void createPrice_validRequest_returnsPriceResponse() {
        var request = new PriceRequest("AAPL", LocalDate.now(), new BigDecimal("185.50"));
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = priceService.createPrice(request);

        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.closePrice()).isEqualByComparingTo("185.50");
        assertThat(result.source()).isEqualTo("manual");
    }

    @Test
    void getLatestPrice_existing_returnsPrice() {
        var price = new PriceEntity("MSFT", LocalDate.now(), new BigDecimal("420.00"), "manual");
        when(priceRepository.findFirstBySymbolOrderByDateDesc("MSFT"))
                .thenReturn(Optional.of(price));

        var result = priceService.getLatestPrice("MSFT");

        assertThat(result.symbol()).isEqualTo("MSFT");
    }

    @Test
    void getLatestPrice_notFound_throwsEntityNotFound() {
        when(priceRepository.findFirstBySymbolOrderByDateDesc("XYZ"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> priceService.getLatestPrice("XYZ"))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
