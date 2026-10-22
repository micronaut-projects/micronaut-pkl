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
package io.micronaut.pkl.maven;

import io.micronaut.json.tree.JsonNode;
import io.micronaut.pkl.generator.PklSchemaGenerator;
import io.micronaut.pkl.generator.SchemaLocator;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a Pkl schema module from the configuration JSON schemas on the project's runtime
 * classpath. It runs after compilation so the project's own {@code @ConfigurationProperties}
 * classes are included, and writes the module into the class output by default so that it is
 * packaged and {@code application.pkl} can start with {@code amends "modulepath:/Micronaut.pkl"}.
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
@Mojo(name = "generate-pkl-schema", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class GeneratePklSchemaMojo extends AbstractMojo {

    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;

    /**
     * The directory the module is written to.
     */
    @Parameter(property = "micronaut.pkl.outputDirectory", defaultValue = "${project.build.outputDirectory}")
    File outputDirectory;

    /**
     * The name of the generated module, which is also its file name.
     */
    @Parameter(property = "micronaut.pkl.moduleName", defaultValue = "Micronaut")
    String moduleName = "Micronaut";

    /**
     * Skips the generation.
     */
    @Parameter(property = "micronaut.pkl.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping Pkl schema generation");
            return;
        }
        List<Path> classpath = new ArrayList<>();
        for (String element : resolveClasspath()) {
            Path path = Path.of(element);
            if (Files.exists(path)) {
                classpath.add(path);
            }
        }
        Map<String, JsonNode> schemas = SchemaLocator.fromPaths(classpath);
        String pkl = PklSchemaGenerator.generate(schemas.values(), moduleName);
        Path output = outputDirectory.toPath().resolve(moduleName + ".pkl");
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, pkl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to write " + output, e);
        }
        getLog().info("Generated Pkl schema " + output + " from " + schemas.size() + " configuration schemas");
    }

    /**
     * Resolves the classpath elements that are scanned for schemas.
     *
     * @return The classpath elements
     * @throws MojoExecutionException If the runtime classpath cannot be resolved
     */
    protected List<String> resolveClasspath() throws MojoExecutionException {
        try {
            return project.getRuntimeClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Unable to resolve the runtime classpath", e);
        }
    }

    /**
     * @return The output directory
     */
    File getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * @return The module name
     */
    String getModuleName() {
        return moduleName;
    }

    /**
     * @return Whether generation is skipped
     */
    boolean isSkip() {
        return skip;
    }
}
