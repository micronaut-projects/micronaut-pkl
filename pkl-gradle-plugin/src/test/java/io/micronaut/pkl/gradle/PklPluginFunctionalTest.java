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

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PklPluginFunctionalTest {

    private static final String MICRONAUT_VERSION = System.getProperty("micronautVersion", "5.2.2");

    @Test
    void generatesTheSchemaFromTheClasspathAndPackagesItAsAResource(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'pkl-testkit'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
plugins {
    id 'java'
    id 'io.micronaut.pkl'
}
repositories {
    mavenCentral()
}
dependencies {
    implementation 'io.micronaut:micronaut-context:%s'
}
""".formatted(MICRONAUT_VERSION));

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("processResources", "--stacktrace")
            .withPluginClasspath()
            .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePklSchema").getOutcome());
        Path generated = projectDir.resolve("build/generated/pkl/Micronaut.pkl");
        assertTrue(Files.exists(generated));
        String pkl = Files.readString(generated);
        assertTrue(pkl.contains("open module Micronaut"));
        assertTrue(pkl.contains("open class MicronautApplicationConfig"));
        assertTrue(pkl.contains("executors: Mapping<String, MicronautExecutorsEntry> = new {}"));
        assertTrue(Files.exists(projectDir.resolve("build/resources/main/Micronaut.pkl")));

        BuildResult secondRun = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("generatePklSchema")
            .withPluginClasspath()
            .build();
        assertEquals(TaskOutcome.UP_TO_DATE, secondRun.task(":generatePklSchema").getOutcome());
    }

    @Test
    void theExtensionRenamesAndRelocatesTheModule(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'pkl-testkit'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
plugins {
    id 'java'
    id 'io.micronaut.pkl'
}
repositories {
    mavenCentral()
}
dependencies {
    implementation 'io.micronaut:micronaut-context:%s'
}
micronautPkl {
    schemaModuleName = 'AppConfig'
    schemaOutputDirectory = layout.projectDirectory.dir('src/main/pkl')
}
""".formatted(MICRONAUT_VERSION));

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("generatePklSchema")
            .withPluginClasspath()
            .build();

        Path generated = projectDir.resolve("src/main/pkl/AppConfig.pkl");
        assertTrue(Files.exists(generated));
        assertTrue(Files.readString(generated).contains("open module AppConfig"));
    }

    @Test
    void generationCanBeDisabled(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'pkl-testkit'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
plugins {
    id 'java'
    id 'io.micronaut.pkl'
}
micronautPkl {
    generateSchema = false
}
""");

        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("generatePklSchema")
            .withPluginClasspath()
            .build();

        assertEquals(TaskOutcome.SKIPPED, result.task(":generatePklSchema").getOutcome());
        assertFalse(Files.exists(projectDir.resolve("build/generated/pkl/Micronaut.pkl")));
    }
}
