package com.wealthview.core.exception;

/**
 * Thrown when an endpoint depends on an optional external integration (Yahoo
 * Finance, Finnhub, Zillow valuation sync, ...) that is not configured in this
 * deployment. Mapped to 503 SERVICE_UNAVAILABLE with the standard error
 * envelope — the one representation of "this feature needs an API key" across
 * the whole API surface.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
