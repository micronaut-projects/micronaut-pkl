package io.micronaut.pkl.env

import io.micronaut.context.env.PropertySource
import io.micronaut.core.io.file.DefaultFileSystemResourceLoader
import io.micronaut.core.io.scan.ClassPathResourceLoader
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * The loader resolves modules through the module path for classpath loaders and through file URIs
 * for file system loaders.
 */
class PklResourceLoaderSpec extends Specification {

    @TempDir
    Path dir

    void "a module from a file system loader is evaluated from its file and may read sibling files"() {
        given:
        Files.writeString(dir.resolve("name.txt"), "orders")
        Files.writeString(dir.resolve("application.pkl"), 'micronaut { application { name = read("file:name.txt").text } }\n')
        PklPropertySourceLoader loader = new PklPropertySourceLoader()

        when:
        Optional<PropertySource> source = loader.load("application", new DefaultFileSystemResourceLoader(dir))

        then:
        source.present
        source.get().get("micronaut.application.name") == "orders"
    }

    void "a module from a class path loader is resolved through the module path"() {
        given:
        PklPropertySourceLoader loader = new PklPropertySourceLoader()

        when:
        Optional<PropertySource> source = loader.load("application", ClassPathResourceLoader.defaultLoader(getClass().classLoader))

        then:
        source.present
        source.get().get("micronaut.application.name") == "pkl-test"
    }
}
