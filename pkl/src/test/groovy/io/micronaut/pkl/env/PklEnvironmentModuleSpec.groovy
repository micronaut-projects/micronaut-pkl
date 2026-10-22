package io.micronaut.pkl.env

import io.micronaut.context.env.ActiveEnvironment
import io.micronaut.context.env.PropertySource
import io.micronaut.context.exceptions.ConfigurationException
import io.micronaut.core.io.file.DefaultFileSystemResourceLoader
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * How an environment module such as application-test.pkl derives from application.pkl.
 */
class PklEnvironmentModuleSpec extends Specification {

    @TempDir
    Path dir

    void "an environment module extends an open production module and may add keys"() {
        given:
        Files.writeString(dir.resolve("application.pkl"), '''
            open module application
            micronaut { application { name = "orders" } }
            datasources { ["default"] { url = "jdbc:postgresql://db/orders"; maximumPoolSize = 20 } }
        '''.stripIndent())
        Files.writeString(dir.resolve("application-test.pkl"), '''
            datasources { ["default"] { url = "jdbc:h2:mem:orders"; maximumPoolSize = super.maximumPoolSize ~/ 4 } }
            orders { stubPayments = true }
        '''.stripIndent())

        when:
        Map<String, Object> properties = load("test")

        then:
        properties["datasources.default.url"] == "jdbc:h2:mem:orders"
        properties["datasources.default.maximumPoolSize"] == 5L
        properties["orders.stubPayments"] == true
        properties["micronaut.application.name"] == "orders" // the derived module carries the complete merged configuration
    }

    void "an environment module amends a production module that is not open"() {
        given:
        Files.writeString(dir.resolve("application.pkl"), '''
            micronaut { application { name = "orders" } }
            datasources { ["default"] { url = "jdbc:postgresql://db/orders" } }
        '''.stripIndent())
        Files.writeString(dir.resolve("application-test.pkl"), '''
            micronaut { application { name = super.name + "-test" } }
            datasources { ["default"] { url = "jdbc:h2:mem:orders"; pooled = true } }
        '''.stripIndent())

        when:
        Map<String, Object> properties = load("test")

        then:
        properties["micronaut.application.name"] == "orders-test"
        properties["datasources.default.url"] == "jdbc:h2:mem:orders"
        properties["datasources.default.pooled"] == true
    }

    void "amending a production module that is not open cannot add a top-level key"() {
        given:
        Files.writeString(dir.resolve("application.pkl"), 'micronaut { application { name = "orders" } }\n')
        Files.writeString(dir.resolve("application-test.pkl"), 'orders { stubPayments = true }\n')

        when:
        load("test")

        then:
        ConfigurationException e = thrown()
        e.message.contains("Cannot find property `orders`")
    }

    void "an environment module with its own header is left alone"() {
        given:
        Files.writeString(dir.resolve("application.pkl"), 'open module application\nmicronaut { application { name = "orders" } }\n')
        Files.writeString(dir.resolve("application-test.pkl"), 'module test\norders { stubPayments = true }\n')

        when:
        Map<String, Object> properties = load("test")

        then:
        properties == ["orders.stubPayments": true]
    }

    private Map<String, Object> load(String environment) {
        PklPropertySourceLoader loader = new PklPropertySourceLoader()
        Optional<PropertySource> source = loader.loadEnv("application", new DefaultFileSystemResourceLoader(dir), ActiveEnvironment.of(environment, 0))
        assert source.present
        Map<String, Object> properties = [:]
        source.get().each { key -> properties[key] = source.get().get(key) }
        properties
    }
}
