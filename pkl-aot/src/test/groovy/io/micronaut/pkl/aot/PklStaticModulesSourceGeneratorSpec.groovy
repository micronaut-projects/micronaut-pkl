package io.micronaut.pkl.aot

import com.squareup.javapoet.JavaFile
import io.micronaut.aot.core.config.DefaultConfiguration
import io.micronaut.aot.core.context.ApplicationContextAnalyzer
import io.micronaut.aot.core.context.DefaultSourceGenerationContext
import io.micronaut.context.env.ActiveEnvironment
import io.micronaut.context.env.MapPropertySource
import io.micronaut.core.io.scan.ClassPathResourceLoader
import io.micronaut.core.optim.StaticOptimizations
import io.micronaut.pkl.env.PklPropertySourceLoader
import io.micronaut.pkl.env.PklStaticModules
import spock.lang.Specification
import spock.lang.TempDir

import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path

class PklStaticModulesSourceGeneratorSpec extends Specification {

    @TempDir
    Path workDir

    void "classpath modules are evaluated at build time into a static optimization"() {
        given:
        DefaultSourceGenerationContext context = context("possible.environments": "dev,runtime,nan")

        when:
        new PklStaticModulesSourceGenerator().generate(context)
        PklStaticModules modules = compileAndLoad(context)

        then:
        context.generatedResources.contains("META-INF/services/" + StaticOptimizations.Loader.name)
        context.buildTimeInitClasses.contains(PklStaticModules.name)

        and: "the precomputed values are those the loader evaluates at run time"
        modules.find("application.pkl") == evaluated(null)
        modules.find("application-dev.pkl") == evaluated("dev")
        modules.find("application.pkl")["greeting.endpoints"] == [["path": "/one", "weight": 1L]]
        modules.find("application.pkl")["micronaut.server.maxRequestSize"] == 10L * 1024 * 1024
        modules.find("application.pkl")["micronaut.server.idleTimeout"] == "90s"
        modules.find("application.pkl")["greeting.placeholder"] == '${GREETING_FROM_ENV:fallback}'
        modules.find("application-dev.pkl")["micronaut.application.name"] == "pkl-aot-dev"

        and: "a module that reads env: or prop: resources is left to run time"
        modules.find("application-runtime.pkl") == null

        and: "so is a module with a value that has no Java literal"
        evaluated("nan")["greeting.ratio"].isNaN()
        modules.find("application-nan.pkl") == null
    }

    void "nothing is generated when the Pkl loader is disabled"() {
        given:
        System.setProperty("micronaut.config.pkl.enabled", "false")
        DefaultSourceGenerationContext context = context([:])

        when:
        new PklStaticModulesSourceGenerator().generate(context)

        then:
        context.generatedJavaFiles.empty
        context.generatedResources.empty

        cleanup:
        System.clearProperty("micronaut.config.pkl.enabled")
    }

    private DefaultSourceGenerationContext context(Map<String, String> config) {
        Properties properties = new Properties()
        properties.putAll(config)
        new DefaultSourceGenerationContext("io.example.aot", ApplicationContextAnalyzer.create(), new DefaultConfiguration(properties), workDir.resolve("resources"))
    }

    private static Map<String, Object> evaluated(String environment) {
        PklPropertySourceLoader loader = new PklPropertySourceLoader()
        ClassPathResourceLoader resourceLoader = ClassPathResourceLoader.defaultLoader(PklStaticModulesSourceGeneratorSpec.classLoader)
        def source = environment == null ? loader.load("application", resourceLoader) : loader.loadEnv("application", resourceLoader, ActiveEnvironment.of(environment, 0))
        ((MapPropertySource) source.get()).asMap()
    }

    private PklStaticModules compileAndLoad(DefaultSourceGenerationContext context) {
        Path sources = workDir.resolve("sources")
        Path classes = Files.createDirectories(workDir.resolve("classes"))
        List<JavaFile> files = context.generatedJavaFiles
        assert files*.typeSpec*.name == [PklStaticModulesSourceGenerator.CLASS_NAME]
        files.each { it.writeTo(sources) }
        Path source = sources.resolve("io/example/aot/${PklStaticModulesSourceGenerator.CLASS_NAME}.java")
        int result = ToolProvider.systemJavaCompiler.run(null, null, null,
            "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString())
        assert result == 0
        URLClassLoader classLoader = new URLClassLoader([classes.toUri().toURL()] as URL[], getClass().classLoader)
        StaticOptimizations.Loader<PklStaticModules> loader = classLoader.loadClass("io.example.aot." + PklStaticModulesSourceGenerator.CLASS_NAME)
            .getDeclaredConstructor().newInstance() as StaticOptimizations.Loader<PklStaticModules>
        loader.load()
    }
}
