package io.micronaut.pkl.env

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import io.micronaut.context.exceptions.ConfigurationException
import spock.lang.Specification

import java.time.Duration

class PklPropertySourceLoaderSpec extends Specification {

    void "application.pkl is loaded automatically"() {
        given:
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).start()
        Environment env = ctx.environment

        expect:
        env.getRequiredProperty("micronaut.application.name", String) == "pkl-test"
        env.getRequiredProperty("micronaut.server.port", Integer) == 8081
        env.getRequiredProperty("datasources.default.url", String) == "jdbc:h2:mem:devDb"
        env.getRequiredProperty("datasources.default.maximum-pool-size", Integer) == 10
        !env.getRequiredProperty("hibernate.cache.queries", Boolean)
        env.getRequiredProperty("flags", List) == ["a", "b"]
        env.getRequiredProperty("pairs.one", Integer) == 1
        env.getRequiredProperty("pairs.two", Integer) == 2
        !env.containsProperty("secret")
        !env.getProperty("absent", String).isPresent()

        cleanup:
        ctx.close()
    }

    void "Pkl durations and data sizes convert to Micronaut types"() {
        given:
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).start()
        Environment env = ctx.environment

        expect:
        env.getRequiredProperty("micronaut.server.read-timeout", String) == "30s"
        env.getRequiredProperty("micronaut.server.read-timeout", Duration) == Duration.ofSeconds(30)
        env.getRequiredProperty("micronaut.server.idle-timeout", String) == "90s"
        env.getRequiredProperty("micronaut.server.idle-timeout", Duration) == Duration.ofSeconds(90)
        env.getRequiredProperty("micronaut.server.max-request-size", Long) == 10L * 1024 * 1024

        cleanup:
        ctx.close()
    }

    void "Micronaut placeholders in Pkl strings are resolved by the Environment"() {
        given:
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).start()

        expect:
        ctx.environment.getRequiredProperty("placeholder", String) == "fallback"

        cleanup:
        ctx.close()
    }

    void "an environment module without a module clause amends application.pkl"() {
        given:
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).environments("test").start()
        Environment env = ctx.environment

        expect:
        env.getRequiredProperty("micronaut.server.port", Integer) == 9090
        env.getRequiredProperty("micronaut.application.name", String) == "pkl-test-test"
        env.getRequiredProperty("hibernate.cache.queries", Boolean)
        env.getRequiredProperty("datasources.default.url", String) == "jdbc:h2:mem:devDb"

        cleanup:
        ctx.close()
    }

    void "an environment module with its own amends clause is left alone"() {
        given:
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).environments("explicit").start()

        expect:
        ctx.environment.getRequiredProperty("micronaut.server.port", Integer) == 7070
        ctx.environment.getRequiredProperty("micronaut.application.name", String) == "pkl-test"

        cleanup:
        ctx.close()
    }

    void "https resources are refused by default"() {
        when:
        ApplicationContext.builder().deduceEnvironment(false).environments("insecure").start()

        then:
        ConfigurationException e = thrown()
        e.message.contains("application-insecure.pkl")
        e.message.contains("https://example.com/secret")
    }

    void "syntax errors report the module and location"() {
        when:
        ApplicationContext.builder().deduceEnvironment(false).environments("broken").start()

        then:
        ConfigurationException e = thrown()
        e.message.contains("application-broken.pkl")
    }

    void "the loader is enabled and registered for the pkl extension"() {
        given:
        PklPropertySourceLoader loader = new PklPropertySourceLoader()

        expect:
        loader.enabled
        loader.extensions == ["pkl"] as Set
        !loader.load("no-such-module", io.micronaut.core.io.scan.ClassPathResourceLoader.defaultLoader(getClass().classLoader)).present
        !loader.load("no-such-module", new io.micronaut.core.io.file.DefaultFileSystemResourceLoader(java.nio.file.Path.of(".") )).present
    }

    void "the loader can be disabled via settings"() {
        given:
        PklLoaderSettings settings = PklLoaderSettings.load({ k -> k == "micronaut.config.pkl.enabled" ? "false" : null }, { k -> null })
        PklPropertySourceLoader loader = new PklPropertySourceLoader(settings)

        expect:
        !loader.enabled
        !loader.load("application", io.micronaut.core.io.scan.ClassPathResourceLoader.defaultLoader(getClass().classLoader)).present
    }

    void "settings are read from system properties and environment variables"() {
        when:
        PklLoaderSettings fromProps = PklLoaderSettings.load(
                { k -> [(PklLoaderSettings.PREFIX + "timeout"): "500ms", (PklLoaderSettings.PREFIX + "allowed-resources"): "https:,file:"][k] },
                { k -> null })
        PklLoaderSettings fromEnv = PklLoaderSettings.load(
                { k -> null },
                { k -> ["MICRONAUT_CONFIG_PKL_TIMEOUT": "2m", "MICRONAUT_CONFIG_PKL_ALLOWED_MODULES": "https:"][k] })
        PklLoaderSettings defaults = PklLoaderSettings.load({ k -> null }, { k -> null })

        then:
        fromProps.timeout() == Duration.ofMillis(500)
        fromProps.allowedResources()*.pattern() == ["https:", "file:"]
        fromProps.allowedModules() == null
        fromEnv.timeout() == Duration.ofMinutes(2)
        fromEnv.allowedModules()*.pattern() == ["https:"]
        defaults.enabled()
        defaults.timeout() == PklLoaderSettings.DEFAULT_TIMEOUT
    }
}

class PklDefaultKeywordSpec extends Specification {

    void "a default element is not a property named default"() {
        given:
        // application-defaultkeyword.pkl amends application.pkl and writes `datasources { default { url = ... } }`
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).environments("defaultkeyword").start()

        expect:
        // Pkl's `default` keyword configures element defaults, so the base module's entry is untouched;
        // the loader logs a warning pointing at the ["default"] entry form
        ctx.environment.getRequiredProperty("datasources.default.url", String) == "jdbc:h2:mem:devDb"

        cleanup:
        ctx.close()
    }
}

class PklModuleHeaderSpec extends Specification {

    void "an environment module with a module header is not amended implicitly"() {
        given:
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).environments("named").start()

        expect:
        ctx.environment.getRequiredProperty("micronaut.server.port", Integer) == 6060
        // the base module still contributes its own property source
        ctx.environment.getRequiredProperty("micronaut.application.name", String) == "pkl-test"

        cleanup:
        ctx.close()
    }

    void "invalid settings name the offending setting"() {
        when:
        PklLoaderSettings.load({ k -> k == PklLoaderSettings.PREFIX + "allowed-resources" ? "https:(" : null }, { k -> null })

        then:
        ConfigurationException e = thrown()
        e.message.contains("micronaut.config.pkl.allowed-resources")

        when:
        PklLoaderSettings.load({ k -> k == PklLoaderSettings.PREFIX + "timeout" ? "10sec" : null }, { k -> null })

        then:
        e = thrown()
        e.message.contains("micronaut.config.pkl.timeout")
    }
}
