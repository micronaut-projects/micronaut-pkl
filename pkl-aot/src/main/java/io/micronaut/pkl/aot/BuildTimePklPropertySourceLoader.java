/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pkl.aot;

import io.micronaut.pkl.env.PklPropertySourceLoader;
import org.jspecify.annotations.Nullable;
import org.pkl.core.EvaluatorBuilder;
import org.pkl.core.SecurityManager;
import org.pkl.core.SecurityManagerException;
import org.pkl.core.externalreader.ExternalReaderProcessException;
import org.pkl.core.module.PathElement;
import org.pkl.core.resource.ResourceReader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Pkl loader used at build time. It records every resource a module reads other than a
 * {@code modulepath:} resource, which is packaged with the application: an {@code env:},
 * {@code prop:}, {@code https:} or {@code file:} resource depends on where the application runs,
 * so a module that reads one cannot be converted to Java.
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
final class BuildTimePklPropertySourceLoader extends PklPropertySourceLoader {

    private static final String PACKAGED_SCHEME = "modulepath";

    private final Set<String> runtimeReads = new LinkedHashSet<>();

    @Override
    protected EvaluatorBuilder createEvaluator(@Nullable ClassLoader classLoader, @Nullable Path rootDir) {
        EvaluatorBuilder builder = super.createEvaluator(classLoader, rootDir);
        List<ResourceReader> readers = builder.getResourceReaders().stream()
            .map(reader -> PACKAGED_SCHEME.equals(reader.getUriScheme()) ? reader : new RecordingResourceReader(reader, runtimeReads))
            .toList();
        return builder.setResourceReaders(readers);
    }

    /**
     * @return The run-time dependent resources read since the last {@link #clearRuntimeReads()}, in reading order
     */
    List<String> runtimeReads() {
        return List.copyOf(runtimeReads);
    }

    void clearRuntimeReads() {
        runtimeReads.clear();
    }

    /**
     * A resource reader that records the URIs it is asked about and delegates everything else.
     *
     * @param delegate The reader to delegate to
     * @param reads    The URIs read, recorded in order
     */
    record RecordingResourceReader(ResourceReader delegate, Set<String> reads) implements ResourceReader {

        @Override
        public String getUriScheme() {
            return delegate.getUriScheme();
        }

        @Override
        public Optional<Object> read(URI uri) throws IOException, URISyntaxException, SecurityManagerException, ExternalReaderProcessException {
            reads.add(uri.toString());
            return delegate.read(uri);
        }

        @Override
        public boolean hasHierarchicalUris() throws ExternalReaderProcessException, IOException {
            return delegate.hasHierarchicalUris();
        }

        @Override
        public boolean isGlobbable() throws ExternalReaderProcessException, IOException {
            return delegate.isGlobbable();
        }

        @Override
        public boolean hasFragmentPaths() {
            return delegate.hasFragmentPaths();
        }

        @Override
        public boolean hasElement(SecurityManager securityManager, URI uri) throws IOException, SecurityManagerException, ExternalReaderProcessException {
            reads.add(uri.toString());
            return delegate.hasElement(securityManager, uri);
        }

        @Override
        public List<PathElement> listElements(SecurityManager securityManager, URI uri) throws IOException, SecurityManagerException, ExternalReaderProcessException {
            reads.add(uri.toString());
            return delegate.listElements(securityManager, uri);
        }

        @Override
        public URI resolveUri(URI baseUri, URI uri) throws IOException, SecurityManagerException {
            return delegate.resolveUri(baseUri, uri);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
