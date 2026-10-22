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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * The {@code io.micronaut.pkl} Gradle plugin. It registers the {@code generatePklSchema} task,
 * which writes a typed Pkl module describing every configuration property known to the modules
 * on the application's classpath, and adds the generated module to the main resources so that
 * {@code application.pkl} can start with {@code amends "modulepath:/Micronaut.pkl"}.
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
public final class PklPlugin implements Plugin<Project> {

    /**
     * The name of the extension.
     */
    public static final String EXTENSION_NAME = "micronautPkl";

    /**
     * The name of the task.
     */
    public static final String GENERATE_SCHEMA_TASK = "generatePklSchema";

    @Override
    public void apply(Project project) {
        PklExtension extension = project.getExtensions().create(EXTENSION_NAME, PklExtension.class);
        extension.getGenerateSchema().convention(true);
        extension.getSchemaModuleName().convention("Micronaut");
        extension.getSchemaOutputDirectory().convention(project.getLayout().getBuildDirectory().dir("generated/pkl"));

        TaskProvider<GeneratePklSchemaTask> generateTask = project.getTasks().register(GENERATE_SCHEMA_TASK, GeneratePklSchemaTask.class, task -> {
            task.setGroup("micronaut");
            task.setDescription("Generates a Pkl schema module from the configuration schemas on the classpath");
            task.getModuleName().convention(extension.getSchemaModuleName());
            task.getOutputDirectory().convention(extension.getSchemaOutputDirectory());
            task.getClasspath().from(extension.getSchemaClasspath());
            task.onlyIf("micronautPkl.generateSchema is false", t -> extension.getGenerateSchema().get());
        });

        project.getPlugins().withType(JavaPlugin.class, ignored -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            // the resolved runtime configuration rather than the source set's runtimeClasspath, which
            // would include processResources output and create a cycle with the generated resource
            extension.getSchemaClasspath().from(
                project.getConfigurations().named(main.getRuntimeClasspathConfigurationName()),
                main.getOutput().getClassesDirs()
            );
            main.getResources().srcDir(generateTask.flatMap(GeneratePklSchemaTask::getOutputDirectory));
        });
    }
}
