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

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the task action in-process (the functional tests run it in a separate Gradle daemon).
 */
class GeneratePklSchemaTaskTest {

    @Test
    void generatesTheModuleFromTheConfiguredClasspath(@TempDir Path tempDir) {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        GeneratePklSchemaTask task = project.getTasks().create("generate", GeneratePklSchemaTask.class);
        List<File> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(entry -> entry.contains("micronaut-context"))
            .map(File::new)
            .toList();
        task.getClasspath().from(classpath, tempDir.resolve("missing").toFile());
        task.getModuleName().set("AppConfig");
        task.getOutputDirectory().set(tempDir.resolve("out").toFile());

        task.generate();

        Path generated = tempDir.resolve("out/AppConfig.pkl");
        assertTrue(Files.exists(generated));
        String pkl;
        try {
            pkl = Files.readString(generated);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertTrue(pkl.contains("open module AppConfig"));
        assertTrue(pkl.contains("open class MicronautApplicationConfig"));
    }
}
