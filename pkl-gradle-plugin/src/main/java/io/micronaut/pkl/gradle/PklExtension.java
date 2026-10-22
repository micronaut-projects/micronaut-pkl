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

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code micronautPkl} extension.
 *
 * <pre>
 * micronautPkl {
 *     schemaModuleName = 'Micronaut'
 *     schemaOutputDirectory = layout.projectDirectory.dir('src/main/pkl')
 * }
 * </pre>
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
public interface PklExtension {

    /**
     * @return Whether to generate the schema module. Defaults to {@code true}
     */
    Property<Boolean> getGenerateSchema();

    /**
     * @return The name of the generated module, which is also its file name. Defaults to {@code Micronaut}
     */
    Property<String> getSchemaModuleName();

    /**
     * @return The directory the schema module is written to. Defaults to {@code build/generated/pkl},
     * which is added to the main resources so the module is on the application's classpath
     */
    DirectoryProperty getSchemaOutputDirectory();

    /**
     * @return The classpath scanned for configuration schemas. Defaults to the runtime classpath
     * plus the main class output, so the application's own configuration classes are included
     */
    ConfigurableFileCollection getSchemaClasspath();
}
