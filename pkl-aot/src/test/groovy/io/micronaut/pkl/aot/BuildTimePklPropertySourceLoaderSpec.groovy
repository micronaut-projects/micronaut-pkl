package io.micronaut.pkl.aot

import spock.lang.Specification

class BuildTimePklPropertySourceLoaderSpec extends Specification {

    void "reads of every resource that is not packaged with the application are recorded"() {
        given:
        def readers = new BuildTimePklPropertySourceLoader().createEvaluator(getClass().classLoader, null).resourceReaders

        when:
        def recorded = readers.findAll { it instanceof BuildTimePklPropertySourceLoader.RecordingResourceReader }*.uriScheme

        then:
        recorded.containsAll(["env", "prop", "file", "https", "package"])
        (readers*.uriScheme - recorded) == ["modulepath"]
    }
}
