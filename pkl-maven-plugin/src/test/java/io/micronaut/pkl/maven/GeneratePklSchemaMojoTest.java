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

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratePklSchemaMojoTest {

    @Test
    void generatesTheModuleFromTheRuntimeClasspath(@TempDir Path tempDir) throws Exception {
        TestMojo mojo = new TestMojo();
        mojo.project = new MavenProject();
        mojo.outputDirectory = tempDir.resolve("classes").toFile();
        mojo.moduleName = "Micronaut";

        mojo.execute();

        Path generated = tempDir.resolve("classes/Micronaut.pkl");
        assertTrue(Files.exists(generated));
        String pkl = Files.readString(generated);
        assertTrue(pkl.contains("open module Micronaut"));
        assertTrue(pkl.contains("open class MicronautApplicationConfig"));
    }

    @Test
    void skipLeavesNothingBehind(@TempDir Path tempDir) throws Exception {
        TestMojo mojo = new TestMojo();
        mojo.project = new MavenProject();
        mojo.outputDirectory = tempDir.resolve("classes").toFile();
        mojo.skip = true;

        mojo.execute();

        assertFalse(Files.exists(tempDir.resolve("classes/Micronaut.pkl")));
    }

    /**
     * Resolves the classpath from the test JVM instead of a resolved Maven project.
     */
    static final class TestMojo extends GeneratePklSchemaMojo {
        @Override
        protected List<String> resolveClasspath() {
            return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.contains("micronaut-context"))
                .toList();
        }
    }
}
