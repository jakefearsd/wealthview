package com.wealthview.core.income;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.income.dto.CreateIncomeSourceRequest;
import com.wealthview.core.income.dto.IncomeSourceResponse;
import com.wealthview.core.income.dto.UpdateIncomeSourceRequest;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class IncomeSourceService {

    private static final Logger log = LoggerFactory.getLogger(IncomeSourceService.class);

    private static final Set<String> VALID_INCOME_TYPES = Set.of(
            "rental_property", "social_security", "pension",
            "part_time_work", "annuity", "other");

    private static final Set<String> VALID_TAX_TREATMENTS = Set.of(
            "taxable", "partially_taxable", "tax_free",
            "rental_passive", "rental_active_reps", "rental_active_str",
            "self_employment");

    private final IncomeSourceRepository incomeSourceRepository;
    private final PropertyRepository propertyRepository;
    private final TenantRepository tenantRepository;

    public IncomeSourceService(IncomeSourceRepository incomeSourceRepository,
                               PropertyRepository propertyRepository,
                               TenantRepository tenantRepository) {
        this.incomeSourceRepository = incomeSourceRepository;
        this.propertyRepository = propertyRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public IncomeSourceResponse create(UUID tenantId, CreateIncomeSourceRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        validateIncomeType(request.incomeType());
        var taxTreatment = request.taxTreatment() != null ? request.taxTreatment() : "taxable";
        validateTaxTreatment(taxTreatment);

        var inflationRate = request.inflationRate() != null ? request.inflationRate() : BigDecimal.ZERO;
        var oneTime = request.oneTime() != null && request.oneTime();

        var entity = new IncomeSourceEntity(tenant, request.name(), request.incomeType(),
                request.annualAmount(), request.startAge(), request.endAge(),
                inflationRate, oneTime, taxTreatment);

        if (request.propertyId() != null) {
            var property = propertyRepository.findByTenant_IdAndId(tenantId, request.propertyId())
                    .orElseThrow(() -> new EntityNotFoundException("Property not found"));
            entity.setProperty(property);
        }

        entity = incomeSourceRepository.save(entity);
        log.info("Income source '{}' created for tenant {}", entity.getName(), tenantId);
        return IncomeSourceResponse.from(entity);
    }

    @Transactional(readOnly = true)
    public List<IncomeSourceResponse> list(UUID tenantId) {
        return incomeSourceRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .map(IncomeSourceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public IncomeSourceResponse get(UUID tenantId, UUID sourceId) {
        var entity = incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId)
                .orElseThrow(() -> new EntityNotFoundException("Income source not found"));
        return IncomeSourceResponse.from(entity);
    }

    @Transactional
    public IncomeSourceResponse update(UUID tenantId, UUID sourceId, UpdateIncomeSourceRequest request) {
        var entity = incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId)
                .orElseThrow(() -> new EntityNotFoundException("Income source not found"));

        validateTaxTreatment(request.taxTreatment());

        entity.setName(request.name());
        entity.setAnnualAmount(request.annualAmount());
        entity.setStartAge(request.startAge());
        entity.setEndAge(request.endAge());
        entity.setInflationRate(request.inflationRate() != null ? request.inflationRate() : BigDecimal.ZERO);
        entity.setOneTime(request.oneTime() != null && request.oneTime());
        entity.setTaxTreatment(request.taxTreatment());

        if (request.propertyId() != null) {
            var property = propertyRepository.findByTenant_IdAndId(tenantId, request.propertyId())
                    .orElseThrow(() -> new EntityNotFoundException("Property not found"));
            entity.setProperty(property);
        } else {
            entity.setProperty(null);
        }

        entity.setUpdatedAt(OffsetDateTime.now());
        entity = incomeSourceRepository.save(entity);
        return IncomeSourceResponse.from(entity);
    }

    @Transactional
    public void delete(UUID tenantId, UUID sourceId) {
        var entity = incomeSourceRepository.findByTenant_IdAndId(tenantId, sourceId)
                .orElseThrow(() -> new EntityNotFoundException("Income source not found"));
        incomeSourceRepository.delete(entity);
        log.info("Income source '{}' deleted for tenant {}", entity.getName(), tenantId);
    }

    private void validateIncomeType(String incomeType) {
        if (!VALID_INCOME_TYPES.contains(incomeType)) {
            throw new IllegalArgumentException(
                    "Invalid income type: " + incomeType + ". Must be one of: " + VALID_INCOME_TYPES);
        }
    }

    private void validateTaxTreatment(String taxTreatment) {
        if (!VALID_TAX_TREATMENTS.contains(taxTreatment)) {
            throw new IllegalArgumentException(
                    "Invalid tax treatment: " + taxTreatment + ". Must be one of: " + VALID_TAX_TREATMENTS);
        }
    }
}
