package io.micronaut.pkl.generator

import io.micronaut.json.tree.JsonNode
import io.micronaut.pkl.env.PklValues
import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource
import org.pkl.core.PModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates a module from the real schemas packaged in the Micronaut modules on the test classpath.
 */
class MicronautSchemasSpec extends Specification {

    @TempDir
    Path dir

    void "the schemas packaged with Micronaut modules generate a module Pkl can evaluate"() {
        given:
        Map<String, JsonNode> schemas = SchemaLocator.fromClassLoader(getClass().classLoader)

        expect:
        schemas.size() > 20
        schemas.containsKey("io.micronaut.http.server.netty.configuration.NettyHttpServerConfiguration.json")
        schemas.containsKey("io.micronaut.scheduling.executor.UserExecutorConfiguration.json")

        when:
        String pkl = PklSchemaGenerator.generate(schemas.values())
        Files.writeString(dir.resolve("Micronaut.pkl"), pkl)
        Path app = dir.resolve("application.pkl")
        Files.writeString(app, '''
            amends "Micronaut.pkl"

            micronaut {
              application { name = "demo" }
              server {
                port = 8080
                netty {
                  serverType = "FULL_CONTENT"
                  parent { threads = 2; shutdownTimeout = 5.s }
                }
              }
              executors {
                ["io"] { type = "FIXED"; nThreads = 4 }
              }
            }
        '''.stripIndent())
        Map<String, Object> flat
        try (Evaluator evaluator = Evaluator.preconfigured()) {
            PModule module = evaluator.evaluate(ModuleSource.path(app))
            flat = PklValues.flatten(PklValues.toMap(module))
        }

        then:
        pkl.contains('open class MicronautServerNettyConfig {')
        pkl.contains('  executors: Mapping<String, MicronautExecutorsEntry> = new {}')
        flat["micronaut.application.name"] == "demo"
        flat["micronaut.server.port"] == 8080L
        flat["micronaut.server.netty.serverType"] == "FULL_CONTENT"
        flat["micronaut.server.netty.parent.threads"] == 2L
        flat["micronaut.server.netty.parent.shutdownTimeout"] == "5s"
        flat["micronaut.executors.io.type"] == "FIXED"
        flat["micronaut.executors.io.nThreads"] == 4L
    }

    void "the command line tool writes the module for a classpath"() {
        given:
        List<String> classpath = System.getProperty("java.class.path").split(File.pathSeparator).findAll { it.contains("micronaut-context") || it.contains("micronaut-http-server-netty") }
        Path out = dir.resolve("gen/Micronaut.pkl")

        when:
        PklSchemaGeneratorCli.main(["--classpath", classpath.join(File.pathSeparator), "--out", out.toString()] as String[])

        then:
        Files.exists(out)
        Files.readString(out).contains('open module Micronaut')
        Files.readString(out).contains('open class MicronautServerNettyConfig {')
    }
}
