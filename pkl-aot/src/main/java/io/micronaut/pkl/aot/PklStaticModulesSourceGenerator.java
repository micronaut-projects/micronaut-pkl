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
package io.micronaut.pkl.aot;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Environments;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.aot.core.codegen.MapGenerator;
import io.micronaut.context.env.ActiveEnvironment;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.io.ResourceLoader;
import io.micronaut.core.io.scan.DefaultClassPathResourceLoader;
import io.micronaut.core.optim.StaticOptimizations;
import io.micronaut.pkl.env.PklStaticModules;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.lang.model.element.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Micronaut AOT optimization that evaluates the application's Pkl configuration modules when the
 * application is built, so that Pkl and its Truffle runtime are not loaded when it starts.
 *
 * <p>It evaluates {@code application.pkl} and {@code bootstrap.pkl} from the classpath, plus the
 * {@code application-{env}.pkl} module of every environment listed in {@code possible.environments}
 * or active during the AOT analysis, and generates a {@link StaticOptimizations.Loader} for
 * {@link PklStaticModules}. The Pkl loader serves those modules from the generated class and still
 * evaluates any other module, such as one for an environment that was not listed. The modules stay
 * in the application, because an environment module evaluated at run time derives from its base module.</p>
 *
 * <p>A module that reads any resource other than a {@code modulepath:} one, such as an {@code env:},
 * {@code prop:} or {@code https:} resource, is not converted, because its value would be the build
 * machine's: it is evaluated when the application starts, and the optimization logs a warning. Use a Micronaut placeholder such as {@code "${PORT:8080}"} instead, which the Environment
 * resolves at run time. The {@code micronaut.config.pkl.*} settings are read from the process that runs
 * the optimizer.</p>
 *
 * <p>To use it, add {@code io.micronaut.pkl:micronaut-pkl-aot} to the AOT optimizer's classpath and set
 * {@code pkl.to.java.config.enabled=true} in the AOT configuration. With Gradle, the {@code io.micronaut.pkl}
 * plugin does both when {@code micronautPkl.convertToJava} is {@code true}. With Maven, declare the artifact
 * in the Micronaut Maven plugin's {@code aotDependencies} and set the property in {@code aot.properties}.</p>
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
@AOTModule(
    id = PklStaticModulesSourceGenerator.ID,
    description = PklStaticModulesSourceGenerator.DESCRIPTION
)
public class PklStaticModulesSourceGenerator extends AbstractCodeGenerator {

    /**
     * The identifier of the optimization.
     */
    public static final String ID = "pkl.to.java.config";

    /**
     * The description of the optimization.
     */
    public static final String DESCRIPTION = "Evaluates Pkl configuration modules at build time and converts them to Java";

    static final String CLASS_NAME = "AotPklStaticModules";

    private static final Logger LOG = LoggerFactory.getLogger(PklStaticModulesSourceGenerator.class);
    private static final List<String> RESOURCE_NAMES = List.of(Environment.DEFAULT_NAME, Environment.BOOTSTRAP_NAME);

    @Override
    public void generate(AOTContext context) {
        BuildTimePklPropertySourceLoader loader = new BuildTimePklPropertySourceLoader();
        if (!loader.isEnabled()) {
            return;
        }
        ResourceLoader resourceLoader = new DefaultClassPathResourceLoader(getClass().getClassLoader());
        Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
        Set<String> environments = environmentNames(context);
        for (String resourceName : RESOURCE_NAMES) {
            convert(loader, resourceLoader, resourceName, null, modules);
            for (String environment : environments) {
                convert(loader, resourceLoader, resourceName, ActiveEnvironment.of(environment, 0), modules);
            }
        }
        if (modules.isEmpty()) {
            return;
        }

        TypeSpec.Builder type = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ParameterizedTypeName.get(StaticOptimizations.Loader.class, PklStaticModules.class));
        TypeName modulesType = ParameterizedTypeName.get(
            ClassName.get(Map.class),
            ClassName.get(String.class),
            ParameterizedTypeName.get(Map.class, String.class, Object.class)
        );
        CodeBlock.Builder body = CodeBlock.builder()
            .addStatement("$T modules = new $T<>()", modulesType, HashMap.class);
        MapGenerator mapGenerator = new MapGenerator();
        modules.forEach((path, properties) ->
            body.addStatement("modules.put($S, $L)", path, mapGenerator.generateMap(type, properties)));
        body.addStatement("return new $T(modules)", PklStaticModules.class);
        type.addMethod(MethodSpec.methodBuilder("load")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(PklStaticModules.class)
            .addCode(body.build())
            .build());

        // the same registrations as AOTContext.registerStaticOptimization, which cannot add the
        // helper methods the map generator needs for lists and nested maps
        context.registerBuildTimeInit(PklStaticModules.class.getName());
        context.registerGeneratedSourceFile(context.javaFile(type.build()));
        context.registerServiceImplementation(StaticOptimizations.Loader.class, CLASS_NAME);
    }

    private static Set<String> environmentNames(AOTContext context) {
        Set<String> names = new TreeSet<>(context.getConfiguration().stringList(Environments.POSSIBLE_ENVIRONMENTS_NAMES));
        names.addAll(context.getAnalyzer().getEnvironmentNames());
        return names;
    }

    private static void convert(BuildTimePklPropertySourceLoader loader,
                                ResourceLoader resourceLoader,
                                String resourceName,
                                @Nullable ActiveEnvironment environment,
                                Map<String, Map<String, Object>> modules) {
        String path = (environment == null ? resourceName : resourceName + "-" + environment.getName()) + ".pkl";
        loader.clearRuntimeReads();
        Optional<PropertySource> source = environment == null
            ? loader.load(resourceName, resourceLoader)
            : loader.loadEnv(resourceName, resourceLoader, environment);
        if (source.isEmpty()) {
            return;
        }
        List<String> runtimeReads = loader.runtimeReads();
        if (!runtimeReads.isEmpty()) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Pkl module [{}] reads {} and is evaluated when the application starts instead of being converted to Java. "
                    + "Use a Micronaut placeholder such as \"${NAME:default}\" for values that must come from the run-time environment.",
                    path, String.join(", ", runtimeReads));
            }
            return;
        }
        if (source.get() instanceof MapPropertySource mapPropertySource) {
            Map<String, Object> properties = mapPropertySource.asMap();
            if (properties.values().stream().anyMatch(PklStaticModulesSourceGenerator::hasNonFiniteNumber)) {
                // NaN and Infinity have no Java literal form
                LOG.warn("Pkl module [{}] contains a NaN or infinite Float and is evaluated when the application starts", path);
                return;
            }
            LOG.info("Converting Pkl module [{}] into Java based configuration", path);
            modules.put(path, properties);
        }
    }

    private static boolean hasNonFiniteNumber(@Nullable Object value) {
        // a Pkl Float is always a Double
        if (value instanceof Double number) {
            return !Double.isFinite(number);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(PklStaticModulesSourceGenerator::hasNonFiniteNumber);
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(PklStaticModulesSourceGenerator::hasNonFiniteNumber);
        }
        return false;
    }
}
