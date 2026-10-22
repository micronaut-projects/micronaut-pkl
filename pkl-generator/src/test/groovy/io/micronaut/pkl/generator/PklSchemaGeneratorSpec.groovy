package io.micronaut.pkl.generator

import io.micronaut.json.tree.JsonNode
import io.micronaut.pkl.env.PklValues
import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource
import org.pkl.core.PModule
import org.pkl.core.PklException
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class PklSchemaGeneratorSpec extends Specification {

    @Shared
    Map<String, JsonNode> exampleSchemas = SchemaLocator.fromPaths([Path.of("src/test/resources/schemas")])

    @Shared
    String example = PklSchemaGenerator.generate(exampleSchemas.values(), "Example")

    @TempDir
    Path dir

    void "schemas in a directory are located and parsed"() {
        expect:
        exampleSchemas.keySet() == ["io.example.ClientConfiguration.json", "io.example.EndpointConfiguration.json", "io.example.HealthEndpoint.json", "io.example.ServerConfiguration.json", "io.example.ServerNettyConfiguration.json"] as Set
    }

    void "prefixes become a class tree with one open class per node"() {
        expect:
        example.contains('open module Example')
        example.contains('example: ExampleConfig = new {}')
        example.contains('open class ExampleConfig {')
        example.contains('  server: ExampleServerConfig = new {}')
        example.contains('  clients: Mapping<String, ExampleClientsEntry> = new {}')
        example.contains('open class ExampleServerConfig {')
        example.contains('  netty: ExampleServerNettyConfig = new {}')
        example.contains('  ssl: ExampleServerSslConfig = new {}')
        example.contains('open class ExampleServerNettyConfig {')
        example.contains('  maxHeaderSize: Int?')
        example.contains('open class ExampleClientsEntry {')
        example.contains('  pool: ExampleClientsEntryPoolConfig = new {}')
        example.contains('  retries: Int(this >= 0)?')
        example.contains('  endpoints: ExampleEndpointsConfig = new {}')
        example.contains('open class ExampleEndpointsConfig {')
        example.contains('  health: ExampleEndpointsHealthConfig = new {}')
        example.contains('open class ExampleEndpointsEntry {')
        example.contains('open class ExampleEndpointsHealthConfig extends ExampleEndpointsEntry {')
    }

    void "property types, constraints, docs and deprecations are translated"() {
        expect:
        example.contains('  port: Int(isBetween(1, 65535))?')
        example.contains('  readTimeout: Duration?')
        example.contains('  mode: ("FAST" | "SAFE")?')
        example.contains('  @Deprecated\n  legacy: Boolean?')
        example.contains('  hosts: Listing<String>(length >= 1)?')
        example.contains('  headers: Mapping<String, Any>?')
        example.contains('  `default`: String?')
        example.contains('  name: String(matches(Regex(#"[a-z]+"#)))?')
        example.contains('  keyPath: String?')
        example.contains('  /// The port. Default value (`DEFAULT_PORT`).\n  ///\n  /// Default: `8080`\n  ///\n  /// Java type: `int`\n  port:')
        example.contains('/// Configures the `Server`.\n///\n/// Second paragraph with `code`.\n///\n/// Configuration prefix: `example.server`\n///\n/// Java type: `io.example.ServerConfiguration`\nopen class ExampleServerConfig')
        example.contains('  /// SSL settings.\n  ssl:')
    }

    void "the generated module evaluates and validates an amending module"() {
        given:
        Path schema = dir.resolve("Example.pkl")
        Files.writeString(schema, example)
        Path app = dir.resolve("application.pkl")
        Files.writeString(app, '''
            amends "Example.pkl"

            example {
              server {
                port = 8081
                readTimeout = 30.s
                mode = "SAFE"
                hosts { "a"; "b" }
                headers { ["X-Trace"] = "on" }
                `default` = "yes"
                ssl { enabled = true }
                netty { maxHeaderSize = 4096 }
              }
              endpoints {
                health { enabled = true; detailsVisible = "NEVER" }
              }
              clients {
                ["billing"] {
                  url = "https://billing"
                  retries = 3
                  pool { size = 2 }
                }
              }
            }
        '''.stripIndent())

        when:
        Map<String, Object> flat
        try (Evaluator evaluator = Evaluator.preconfigured()) {
            PModule module = evaluator.evaluate(ModuleSource.path(app))
            flat = PklValues.flatten(PklValues.toMap(module))
        }

        then:
        flat["example.server.port"] == 8081L
        flat["example.server.readTimeout"] == "30s"
        flat["example.server.mode"] == "SAFE"
        flat["example.server.hosts"] == ["a", "b"]
        flat["example.server.headers.X-Trace"] == "on"
        flat["example.server.default"] == "yes"
        flat["example.server.ssl.enabled"] == true
        flat["example.server.netty.maxHeaderSize"] == 4096L
        flat["example.clients.billing.url"] == "https://billing"
        flat["example.clients.billing.retries"] == 3L
        flat["example.clients.billing.pool.size"] == 2L
        flat["example.endpoints.health.enabled"] == true
        flat["example.endpoints.health.detailsVisible"] == "NEVER"
        // untouched nullable properties and empty objects do not produce keys
        !flat.containsKey("example.server.legacy")
        !flat.containsKey("example.server.name")
        !flat.containsKey("example.server.ssl.keyPath")
    }

    void "a misspelled or invalid property is rejected"() {
        given:
        Files.writeString(dir.resolve("Example.pkl"), example)
        Path app = dir.resolve("application.pkl")
        Files.writeString(app, "amends \"Example.pkl\"\n\n" + body)

        when:
        try (Evaluator evaluator = Evaluator.preconfigured()) {
            evaluator.evaluate(ModuleSource.path(app))
        }

        then:
        PklException e = thrown()
        e.message.contains(expectedMessage)

        where:
        body                                              | expectedMessage
        'example { server { prot = 1 } }'                 | 'Cannot find property `prot`'
        'example { server { port = 70000 } }'             | 'Type constraint `isBetween(1, 65535)` violated'
        'example { server { mode = "SLOW" } }'            | 'Expected value of type `"FAST"|"SAFE"`'
        'example { server { readTimeout = "30s" } }'      | 'Expected value of type `Duration`'
    }
}
