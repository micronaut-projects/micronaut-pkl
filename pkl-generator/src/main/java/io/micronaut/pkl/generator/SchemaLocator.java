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
package io.micronaut.pkl.generator;

import io.micronaut.jackson.core.tree.JsonNodeTreeCodec;
import io.micronaut.json.tree.JsonNode;
import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finds the configuration JSON schemas that Micronaut modules package under
 * {@value #SCHEMAS_FOLDER} and parses them.
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
public final class SchemaLocator {

    /**
     * The folder inside a JAR or classes directory that holds the schemas.
     */
    public static final String SCHEMAS_FOLDER = "META-INF/micronaut-configuration-schemas";

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private SchemaLocator() {
    }

    /**
     * Finds every schema visible to the given class loader.
     *
     * @param classLoader The class loader
     * @return The parsed schemas keyed by the name of the file they were read from, sorted by name
     */
    public static Map<String, JsonNode> fromClassLoader(ClassLoader classLoader) {
        Map<String, JsonNode> schemas = new TreeMap<>();
        try {
            Enumeration<URL> roots = classLoader.getResources(SCHEMAS_FOLDER);
            for (URL root : Collections.list(roots)) {
                readRoot(root.toURI(), schemas);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return schemas;
    }

    /**
     * Finds every schema in the given classpath entries.
     *
     * @param entries JAR files or class directories. A directory that itself contains schema files is also accepted
     * @return The parsed schemas keyed by the name of the file they were read from, sorted by name
     */
    public static Map<String, JsonNode> fromPaths(Collection<Path> entries) {
        Map<String, JsonNode> schemas = new TreeMap<>();
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                Path folder = entry.resolve(SCHEMAS_FOLDER);
                readDirectory(Files.isDirectory(folder) ? folder : entry, schemas);
            } else if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                try (FileSystem jar = FileSystems.newFileSystem(entry, (ClassLoader) null)) {
                    Path folder = jar.getPath(SCHEMAS_FOLDER);
                    if (Files.isDirectory(folder)) {
                        readDirectory(folder, schemas);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to read " + entry, e);
                }
            }
        }
        return schemas;
    }

    /**
     * Parses a single schema document.
     *
     * @param input The JSON text
     * @return The parsed schema
     * @throws IOException If the input cannot be read
     */
    public static JsonNode parse(InputStream input) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(ObjectReadContext.empty(), input)) {
            return JsonNodeTreeCodec.getInstance().readTree(parser);
        }
    }

    private static void readRoot(URI root, Map<String, JsonNode> schemas) throws IOException {
        if ("jar".equals(root.getScheme())) {
            try (FileSystem jar = FileSystems.newFileSystem(root, Map.of())) {
                readDirectory(jar.provider().getPath(root), schemas);
            } catch (java.nio.file.FileSystemAlreadyExistsException e) {
                readDirectory(Path.of(root), schemas);
            }
        } else {
            readDirectory(Path.of(root), schemas);
        }
    }

    private static void readDirectory(Path folder, Map<String, JsonNode> schemas) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to list " + folder, e);
        }
        for (Path file : files) {
            try (InputStream input = Files.newInputStream(file)) {
                schemas.put(file.getFileName().toString(), parse(input));
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read " + file, e);
            }
        }
    }
}
