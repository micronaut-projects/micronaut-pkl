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
package io.micronaut.pkl.gradle;

import io.micronaut.json.tree.JsonNode;
import io.micronaut.pkl.generator.PklSchemaGenerator;
import io.micronaut.pkl.generator.SchemaLocator;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a Pkl schema module from the configuration JSON schemas found on a classpath.
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
@CacheableTask
public abstract class GeneratePklSchemaTask extends DefaultTask {

    /**
     * @return The classpath scanned for {@code META-INF/micronaut-configuration-schemas}
     */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    /**
     * @return The module name, also used as the file name
     */
    @Input
    public abstract Property<String> getModuleName();

    /**
     * @return The directory the module is written to
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Generates the module.
     */
    @TaskAction
    public void generate() {
        List<Path> entries = new ArrayList<>();
        for (File file : getClasspath().getFiles()) {
            if (file.exists()) {
                entries.add(file.toPath());
            }
        }
        Map<String, JsonNode> schemas = SchemaLocator.fromPaths(entries);
        String moduleName = getModuleName().get();
        String pkl = PklSchemaGenerator.generate(schemas.values(), moduleName);
        Path output = getOutputDirectory().get().getAsFile().toPath().resolve(moduleName + ".pkl");
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, pkl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write " + output, e);
        }
        getLogger().lifecycle("Generated Pkl schema {} from {} configuration schemas", output, schemas.size());
    }
}
