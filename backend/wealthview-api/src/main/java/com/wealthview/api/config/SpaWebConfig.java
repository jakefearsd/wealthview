package com.wealthview.api.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("file:/app/static/", "classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = location.createRelative(resourcePath);
                        if (resource.exists() && resource.isReadable()) {
                            return resource;
                        }
                        // SPA fallback: return index.html for non-API, non-file paths
                        if (!resourcePath.startsWith("api/")) {
                            Resource indexResource = new ClassPathResource("/static/index.html");
                            if (indexResource.exists()) {
                                return indexResource;
                            }
                            // Try filesystem path for Docker deployment
                            Resource fileIndex = location.createRelative("index.html");
                            if (fileIndex.exists()) {
                                return fileIndex;
                            }
                        }
                        return null;
                    }
                });
    }
}
