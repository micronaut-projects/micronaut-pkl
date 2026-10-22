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
package io.micronaut.pkl.env;

import io.micronaut.context.env.AbstractPropertySourceLoader;
import io.micronaut.context.env.ActiveEnvironment;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.PropertySourceLoader;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.ResourceLoader;
import io.micronaut.core.io.file.FileSystemResourceLoader;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.reflect.ClassUtils;
import org.jspecify.annotations.Nullable;
import org.pkl.core.Evaluator;
import org.pkl.core.EvaluatorBuilder;
import org.pkl.core.ModuleSource;
import org.pkl.core.PModule;
import org.pkl.core.PklException;
import org.pkl.core.SecurityManagers;
import org.pkl.core.StackFrameTransformers;
import org.pkl.core.module.ModuleKeyFactories;
import org.pkl.core.resource.ResourceReaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link PropertySourceLoader} that evaluates {@code application.pkl} and
 * {@code application-{env}.pkl} modules written in <a href="https://pkl-lang.org">Apple Pkl</a>
 * and exposes the result as configuration.
 *
 * <p>An environment-specific module that declares no {@code module} header and no {@code amends} or {@code extends}
 * clause implicitly amends the base module, so it only needs to state what differs.
 * Evaluation runs in a sandbox: by default a module may import {@code pkl:} and
 * {@code modulepath:} modules and read {@code env:}, {@code prop:} and {@code modulepath:}
 * resources; a module loaded from the file system may also read files below its own
 * directory. Additional patterns can be allowed with the {@code micronaut.config.pkl.allowed-modules}
 * and {@code micronaut.config.pkl.allowed-resources} system properties (or the equivalent
 * {@code MICRONAUT_CONFIG_PKL_*} environment variables), and evaluation is bounded by
 * {@code micronaut.config.pkl.timeout} (10 seconds by default).</p>
 *
 * <p>This loader deliberately does not extend {@link AbstractPropertySourceLoader}: the
 * environment reads such loaders from an {@link InputStream}, which would hide the module's
 * location and break relative {@code amends} and {@code import} clauses.</p>
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
public class PklPropertySourceLoader implements PropertySourceLoader, Ordered {

    /**
     * The file extension handled by this loader.
     */
    public static final String EXTENSION = "pkl";

    private static final String PKL_EVALUATOR_CLASS = "org.pkl.core.Evaluator";
    private static final String INTERPRETER_WARNING_PROPERTY = "polyglot.engine.WarnInterpreterOnly";
    // a module header or an explicit amends/extends clause: the loader must not prepend its own clause
    private static final Pattern MODULE_CLAUSE = Pattern.compile("(?m)^\\s*(?:(?:abstract|open)\\s+)*(?:module\\s|amends\\s+\"|extends\\s+\")");
    private static final Pattern DEFAULT_ELEMENT = Pattern.compile("(?m)^(\\s*)default\\s*[{=]");
    private static final List<Pattern> DEFAULT_ALLOWED_MODULES = List.of(
        Pattern.compile("pkl:"),
        Pattern.compile("repl:"),
        Pattern.compile("modulepath:")
    );
    private static final List<Pattern> DEFAULT_ALLOWED_RESOURCES = List.of(
        Pattern.compile("env:"),
        Pattern.compile("prop:"),
        Pattern.compile("modulepath:")
    );
    private static final Pattern FILE_PATTERN = Pattern.compile("file:");

    private final PklLoaderSettings settings;

    /**
     * Default constructor, reading the loader settings from system properties and
     * environment variables.
     */
    public PklPropertySourceLoader() {
        this(PklLoaderSettings.load());
    }

    /**
     * Constructor for tests.
     *
     * @param settings The settings
     */
    PklPropertySourceLoader(PklLoaderSettings settings) {
        this.settings = settings;
    }

    @Override
    public Set<String> getExtensions() {
        return Set.of(EXTENSION);
    }

    @Override
    public int getOrder() {
        return AbstractPropertySourceLoader.DEFAULT_POSITION;
    }

    @Override
    public boolean isEnabled() {
        return settings.enabled() && ClassUtils.isPresent(PKL_EVALUATOR_CLASS, PklPropertySourceLoader.class.getClassLoader());
    }

    @Override
    public Optional<PropertySource> load(String resourceName, ResourceLoader resourceLoader) {
        return loadModule(resourceLoader, resourceName, null, getOrder());
    }

    @Override
    public Optional<PropertySource> loadEnv(String resourceName, ResourceLoader resourceLoader, ActiveEnvironment activeEnvironment) {
        return loadModule(
            resourceLoader,
            resourceName + "-" + activeEnvironment.getName(),
            resourceName,
            getOrder() + 1 + activeEnvironment.getPriority()
        );
    }

    /**
     * Evaluates a module given only its contents, as happens for files listed in
     * {@code micronaut.config.files}. Relative {@code amends} and {@code import}
     * clauses cannot be resolved in this mode because the module's location is unknown.
     *
     * @param name  The name of the property source
     * @param input The module text
     * @return The flattened properties
     * @throws IOException If the input cannot be read
     */
    @Override
    public Map<String, Object> read(String name, InputStream input) throws IOException {
        String text = IOUtils.readText(new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
        return evaluate(name, ModuleSource.text(text), null, null, text);
    }

    private Optional<PropertySource> loadModule(ResourceLoader resourceLoader, String fileName, @Nullable String baseName, int order) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String path = fileName + "." + EXTENSION;
        Optional<URL> resource = resourceLoader.getResource(path);
        if (resource.isEmpty()) {
            log().debug("No PropertySource found for file name: {}", path);
            return Optional.empty();
        }
        log().debug("Found PropertySource for file name: {}", path);
        URL url = resource.get();
        String text = readText(resourceLoader, path);
        if (baseName != null && !MODULE_CLAUSE.matcher(text).find() && resourceLoader.getResource(baseName + "." + EXTENSION).isPresent()) {
            // Pkl-idiomatic environment files: amend the base module unless the file declares its own clause
            text = "amends \"" + baseName + "." + EXTENSION + "\"\n" + text;
        }

        ClassLoader classLoader = classLoaderOf(resourceLoader);
        // Everything that is not an explicit file system location is a classpath resource: the Environment
        // itself (which the DefaultEnvironment passes for the classpath:/ location), a ClassPathResourceLoader,
        // or a GraalVM native image resource. Those resolve through the module path.
        ModuleSource source;
        Path rootDir = null;
        if (resourceLoader instanceof FileSystemResourceLoader) {
            Path file = toPath(url);
            rootDir = file.getParent();
            source = ModuleSource.create(file.toUri(), text);
        } else {
            source = ModuleSource.create(URI.create("modulepath:/" + path), text);
        }

        Map<String, Object> properties = evaluate(path, source, classLoader, rootDir, text);
        if (properties.isEmpty()) {
            return Optional.empty();
        }
        PropertySource.Origin origin = PropertySource.Origin.of(path);
        return Optional.of(new MapPropertySource(fileName, properties) {
            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public Origin getOrigin() {
                return origin;
            }
        });
    }

    private Map<String, Object> evaluate(String name, ModuleSource source, @Nullable ClassLoader classLoader, @Nullable Path rootDir, String text) {
        warnAboutDefaultElements(name, text);
        try (Evaluator evaluator = createEvaluator(classLoader, rootDir).build()) {
            PModule module = evaluator.evaluate(source);
            return PklValues.flatten(PklValues.toMap(module));
        } catch (PklException e) {
            throw new ConfigurationException("Error evaluating Pkl module [" + name + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Creates the evaluator builder used to evaluate a module. Subclasses may override this
     * method to customise the evaluator, for example to register additional resource readers.
     *
     * @param classLoader The class loader used to resolve {@code modulepath:} URIs, may be {@code null}
     * @param rootDir     The directory file access is restricted to, or {@code null} to disallow file access
     * @return The builder
     */
    protected EvaluatorBuilder createEvaluator(@Nullable ClassLoader classLoader, @Nullable Path rootDir) {
        if (System.getProperty(INTERPRETER_WARNING_PROPERTY) == null) {
            // Truffle warns on JDKs without the Graal compiler; configuration evaluation is short-lived
            System.setProperty(INTERPRETER_WARNING_PROPERTY, "false");
        }
        List<Pattern> allowedModules = new ArrayList<>(DEFAULT_ALLOWED_MODULES);
        List<Pattern> allowedResources = new ArrayList<>(DEFAULT_ALLOWED_RESOURCES);
        if (rootDir != null) {
            allowedModules.add(FILE_PATTERN);
            allowedResources.add(FILE_PATTERN);
        }
        if (settings.allowedModules() != null) {
            allowedModules.addAll(settings.allowedModules());
        }
        if (settings.allowedResources() != null) {
            allowedResources.addAll(settings.allowedResources());
        }
        ClassLoader resolvingClassLoader = classLoader != null ? classLoader : PklPropertySourceLoader.class.getClassLoader();
        EvaluatorBuilder builder = EvaluatorBuilder.unconfigured()
            .setStackFrameTransformer(StackFrameTransformers.defaultTransformer)
            .setSecurityManager(SecurityManagers.standard(allowedModules, allowedResources, SecurityManagers.defaultTrustLevels, rootDir))
            .addModuleKeyFactory(ModuleKeyFactories.standardLibrary)
            .addModuleKeyFactory(ModuleKeyFactories.classPath(resolvingClassLoader))
            .addModuleKeyFactory(ModuleKeyFactories.file)
            .addModuleKeyFactory(ModuleKeyFactories.http)
            .addModuleKeyFactory(ModuleKeyFactories.pkg)
            .addModuleKeyFactory(ModuleKeyFactories.genericUrl)
            .addResourceReader(ResourceReaders.environmentVariable())
            .addResourceReader(ResourceReaders.externalProperty())
            .addResourceReader(ResourceReaders.classPath(resolvingClassLoader))
            .addResourceReader(ResourceReaders.file())
            .addResourceReader(ResourceReaders.https())
            .addResourceReader(ResourceReaders.pkg())
            .setEnvironmentVariables(System.getenv())
            .setTimeout(settings.timeout());
        System.getProperties().forEach((k, v) -> builder.addExternalProperty(String.valueOf(k), String.valueOf(v)));
        return builder;
    }

    /**
     * The logger is looked up on demand rather than held in a static field: GraalVM initialises this
     * class at image build time, and a static logger would end up in the image heap.
     *
     * @return The logger
     */
    private static Logger log() {
        return LoggerFactory.getLogger(PklPropertySourceLoader.class);
    }

    private static void warnAboutDefaultElements(String name, String text) {
        Matcher matcher = DEFAULT_ELEMENT.matcher(text);
        if (matcher.find() && !matcher.group(1).isEmpty()) {
            log().warn("Pkl module [{}] sets a `default` element. In Pkl `default` is a keyword that configures the "
                + "default value of an object's elements and is not a property named 'default'. For a Micronaut key "
                + "such as datasources.default write it as an entry: datasources { [\"default\"] { ... } }", name);
        }
    }

    private static String readText(ResourceLoader resourceLoader, String path) {
        Optional<InputStream> stream = resourceLoader.getResourceAsStream(path);
        if (stream.isEmpty()) {
            throw new ConfigurationException("Unable to read Pkl module [" + path + "]");
        }
        try (InputStream input = stream.get()) {
            return IOUtils.readText(new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new ConfigurationException("I/O exception occurred reading [" + path + "]: " + e.getMessage(), e);
        }
    }

    private static ClassLoader classLoaderOf(ResourceLoader resourceLoader) {
        if (resourceLoader instanceof ClassPathResourceLoader classPathResourceLoader) {
            return classPathResourceLoader.getClassLoader();
        }
        if (resourceLoader instanceof Environment environment) {
            return environment.getClassLoader();
        }
        return PklPropertySourceLoader.class.getClassLoader();
    }

    private static Path toPath(URL url) {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new ConfigurationException("Invalid Pkl module location [" + url + "]: " + e.getMessage(), e);
        }
    }
}
