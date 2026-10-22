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
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PklPluginTest {

    @Test
    void pluginRegistersTaskDefaultsAndResourceDirectory(@TempDir Path tempDir) {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply(PklPlugin.class);
        project.getPluginManager().apply(JavaPlugin.class);

        PklExtension extension = project.getExtensions().getByType(PklExtension.class);
        GeneratePklSchemaTask task = (GeneratePklSchemaTask) project.getTasks().getByName(PklPlugin.GENERATE_SCHEMA_TASK);
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        Path outputDirectory = project.getProjectDir().toPath().resolve("build/generated/pkl");

        assertTrue(extension.getGenerateSchema().get());
        assertEquals("Micronaut", task.getModuleName().get());
        assertEquals(outputDirectory, task.getOutputDirectory().get().getAsFile().toPath());
        assertTrue(sourceSets.getByName("main").getResources().getSrcDirs().contains(outputDirectory.toFile()));
    }

    @Test
    void extensionConfiguresTheTask(@TempDir Path tempDir) {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(PklPlugin.class);
        PklExtension extension = project.getExtensions().getByType(PklExtension.class);
        extension.getSchemaModuleName().set("AppConfig");
        extension.getSchemaOutputDirectory().set(tempDir.resolve("src/main/pkl").toFile());

        GeneratePklSchemaTask task = (GeneratePklSchemaTask) project.getTasks().getByName(PklPlugin.GENERATE_SCHEMA_TASK);

        assertEquals("AppConfig", task.getModuleName().get());
        assertEquals(tempDir.resolve("src/main/pkl"), task.getOutputDirectory().get().getAsFile().toPath());
    }
}
