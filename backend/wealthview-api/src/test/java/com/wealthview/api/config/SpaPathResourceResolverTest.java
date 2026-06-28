package com.wealthview.api.config;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpaPathResourceResolverTest {

    /** Resolver whose classpath index lookup is controlled by the test rather than the real classpath. */
    private static final class TestableResolver extends SpaPathResourceResolver {
        private final Resource classpathIndex;

        private TestableResolver(Resource classpathIndex) {
            this.classpathIndex = classpathIndex;
        }

        @Override
        Resource classpathIndex() {
            return classpathIndex;
        }
    }

    private static Resource resourceThatExists() {
        Resource r = mock(Resource.class);
        lenient().when(r.exists()).thenReturn(true);
        lenient().when(r.isReadable()).thenReturn(true);
        return r;
    }

    private static Resource missingResource() {
        Resource r = mock(Resource.class);
        lenient().when(r.exists()).thenReturn(false);
        return r;
    }

    @Test
    void getResource_existingReadableAsset_returnsThatAsset() throws IOException {
        Resource asset = resourceThatExists();
        Resource location = mock(Resource.class);
        when(location.createRelative("app.js")).thenReturn(asset);
        var resolver = new TestableResolver(resourceThatExists());

        Resource result = resolver.getResource("app.js", location);

        assertThat(result).isSameAs(asset);
    }

    @Test
    void getResource_assetExistsButNotReadable_fallsThroughToSpaFallback() throws IOException {
        Resource existsButUnreadable = mock(Resource.class);
        when(existsButUnreadable.exists()).thenReturn(true);
        when(existsButUnreadable.isReadable()).thenReturn(false);
        Resource location = mock(Resource.class);
        when(location.createRelative("dashboard")).thenReturn(existsButUnreadable);
        Resource classpathIndex = resourceThatExists();
        var resolver = new TestableResolver(classpathIndex);

        Resource result = resolver.getResource("dashboard", location);

        assertThat(result).isSameAs(classpathIndex);
    }

    @Test
    void getResource_apiPathWithNoMatchingAsset_returnsNullWithoutFallback() throws IOException {
        Resource missing = missingResource();
        Resource location = mock(Resource.class);
        when(location.createRelative("api/v1/accounts")).thenReturn(missing);
        // Classpath index exists, but the api/ guard must short-circuit before reaching it.
        var resolver = new TestableResolver(resourceThatExists());

        Resource result = resolver.getResource("api/v1/accounts", location);

        assertThat(result).isNull();
    }

    @Test
    void getResource_nonApiPathWithClasspathIndex_returnsClasspathIndex() throws IOException {
        Resource missing = missingResource();
        Resource location = mock(Resource.class);
        when(location.createRelative("dashboard")).thenReturn(missing);
        Resource classpathIndex = resourceThatExists();
        var resolver = new TestableResolver(classpathIndex);

        Resource result = resolver.getResource("dashboard", location);

        assertThat(result).isSameAs(classpathIndex);
    }

    @Test
    void getResource_nonApiPathWithoutClasspathIndexButFilesystemIndex_returnsFilesystemIndex() throws IOException {
        Resource missing = missingResource();
        Resource fileIndex = resourceThatExists();
        Resource location = mock(Resource.class);
        when(location.createRelative("dashboard")).thenReturn(missing);
        when(location.createRelative("index.html")).thenReturn(fileIndex);
        var resolver = new TestableResolver(missingResource());

        Resource result = resolver.getResource("dashboard", location);

        assertThat(result).isSameAs(fileIndex);
    }

    @Test
    void getResource_nonApiPathWithNoIndexAvailable_returnsNull() throws IOException {
        Resource missingAsset = missingResource();
        Resource missingFileIndex = missingResource();
        Resource location = mock(Resource.class);
        when(location.createRelative("dashboard")).thenReturn(missingAsset);
        when(location.createRelative("index.html")).thenReturn(missingFileIndex);
        var resolver = new TestableResolver(missingResource());

        Resource result = resolver.getResource("dashboard", location);

        assertThat(result).isNull();
    }
}
