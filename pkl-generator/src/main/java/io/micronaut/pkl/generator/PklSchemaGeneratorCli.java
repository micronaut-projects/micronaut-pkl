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

import io.micronaut.json.tree.JsonNode;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point that writes a Pkl schema module for the configuration schemas found
 * on a classpath.
 *
 * <pre>
 * java -cp micronaut-pkl-generator.jar:... io.micronaut.pkl.generator.PklSchemaGeneratorCli \
 *     --classpath lib/a.jar:lib/b.jar:build/classes/java/main \
 *     --out src/main/resources/Micronaut.pkl
 * </pre>
 *
 * <p>Without {@code --classpath} the schemas visible to this class's class loader are used, so the
 * tool can be run with the application's runtime classpath directly.</p>
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
public final class PklSchemaGeneratorCli {

    private PklSchemaGeneratorCli() {
    }

    /**
     * Runs the generator.
     *
     * @param args {@code --classpath <entries>} (separated by the platform path separator),
     *             {@code --out <file>} (defaults to {@code Micronaut.pkl}) and
     *             {@code --module-name <name>} (defaults to the file name without extension)
     * @throws IOException If the output cannot be written
     */
    @SuppressWarnings("java:S106") // a command line tool reports on the console
    public static void main(String[] args) throws IOException {
        List<Path> classpath = new ArrayList<>();
        Path out = Path.of(PklSchemaGenerator.DEFAULT_MODULE_NAME + ".pkl");
        String moduleName = null;
        Iterator<String> arguments = List.of(args).iterator();
        while (arguments.hasNext()) {
            String argument = arguments.next();
            switch (argument) {
                case "--classpath", "-cp" -> {
                    for (String entry : value(argument, arguments).split(File.pathSeparator)) {
                        if (!entry.isBlank()) {
                            classpath.add(Path.of(entry.trim()));
                        }
                    }
                }
                case "--out", "-o" -> out = Path.of(value(argument, arguments));
                case "--module-name", "-m" -> moduleName = value(argument, arguments);
                case "--help", "-h" -> {
                    usage(System.out);
                    return;
                }
                default -> {
                    usage(System.err);
                    throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
        }
        if (moduleName == null) {
            String fileName = out.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            moduleName = dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        Map<String, JsonNode> schemas = classpath.isEmpty()
            ? SchemaLocator.fromClassLoader(PklSchemaGeneratorCli.class.getClassLoader())
            : SchemaLocator.fromPaths(classpath);
        String pkl = PklSchemaGenerator.generate(schemas.values(), moduleName);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, pkl, StandardCharsets.UTF_8);
        System.out.println("Wrote " + out + " from " + schemas.size() + " configuration schemas");
    }

    @SuppressWarnings("java:S106") // a command line tool reports on the console
    private static String value(String option, Iterator<String> arguments) {
        if (!arguments.hasNext()) {
            usage(System.err);
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return arguments.next();
    }

    @SuppressWarnings("java:S106") // a command line tool reports on the console
    private static void usage(PrintStream stream) {
        stream.println("Usage: PklSchemaGeneratorCli [--classpath <entries>] [--out <file>] [--module-name <name>]");
    }
}
