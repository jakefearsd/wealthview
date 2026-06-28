package com.wealthview.api.config;

import java.io.IOException;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Resource resolver implementing the single-page-app fallback. It serves the
 * requested static asset when it exists, otherwise returns {@code index.html}
 * for any non-API path so client-side routing can take over. API paths and
 * paths with no available {@code index.html} resolve to {@code null} (404).
 */
class SpaPathResourceResolver extends PathResourceResolver {

    private static final String INDEX_HTML = "index.html";

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource resource = location.createRelative(resourcePath);
        if (resource.exists() && resource.isReadable()) {
            return resource;
        }
        // SPA fallback only applies to non-API paths.
        if (resourcePath.startsWith("api/")) {
            return null;
        }
        Resource classpathIndex = classpathIndex();
        if (classpathIndex.exists()) {
            return classpathIndex;
        }
        // Filesystem index for Docker deployment.
        Resource fileIndex = location.createRelative(INDEX_HTML);
        if (fileIndex.exists()) {
            return fileIndex;
        }
        return null;
    }

    /** Classpath index resource; overridable in tests to exercise this branch deterministically. */
    Resource classpathIndex() {
        return new ClassPathResource("/static/index.html");
    }
}
