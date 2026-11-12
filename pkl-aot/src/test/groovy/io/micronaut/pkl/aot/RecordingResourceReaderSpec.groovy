package io.micronaut.pkl.aot

import org.pkl.core.SecurityManager
import org.pkl.core.module.PathElement
import org.pkl.core.resource.ResourceReader
import spock.lang.Specification

class RecordingResourceReaderSpec extends Specification {

    void "every call is delegated and the URIs asked about are recorded in order"() {
        given:
        ResourceReader delegate = Mock()
        SecurityManager securityManager = Mock()
        Set<String> reads = new LinkedHashSet<>()
        def reader = new BuildTimePklPropertySourceLoader.RecordingResourceReader(delegate, reads)
        URI one = URI.create("env:ONE")
        URI glob = URI.create("env:*")
        List<PathElement> elements = [new PathElement("ONE", false)]

        when:
        def scheme = reader.uriScheme
        def value = reader.read(one)
        def hierarchical = reader.hasHierarchicalUris()
        def globbable = reader.globbable
        def fragmentPaths = reader.hasFragmentPaths()
        def hasElement = reader.hasElement(securityManager, one)
        def listed = reader.listElements(securityManager, glob)
        def resolved = reader.resolveUri(glob, one)
        reader.close()

        then:
        1 * delegate.getUriScheme() >> "env"
        1 * delegate.read(one) >> Optional.of("1")
        1 * delegate.hasHierarchicalUris() >> false
        1 * delegate.isGlobbable() >> true
        1 * delegate.hasFragmentPaths() >> false
        1 * delegate.hasElement(securityManager, one) >> true
        1 * delegate.listElements(securityManager, glob) >> elements
        1 * delegate.resolveUri(glob, one) >> one
        1 * delegate.close()

        and:
        scheme == "env"
        value == Optional.of("1")
        !hierarchical
        globbable
        !fragmentPaths
        hasElement
        listed == elements
        resolved == one
        reads as List == ["env:ONE", "env:*"]
    }
}
