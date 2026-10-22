package io.micronaut.pkl.env

import org.pkl.core.DataSize
import org.pkl.core.Duration
import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource
import org.pkl.core.PModule
import spock.lang.Specification

class PklValuesSpec extends Specification {

    void "durations render in Micronaut's short form"() {
        expect:
        PklValues.toDurationString(duration) == expected

        where:
        duration                 | expected
        Duration.ofSeconds(30)   | "30s"
        Duration.ofMinutes(5)    | "5m"
        Duration.ofMinutes(1.5)  | "90s"
        Duration.ofHours(1.5)    | "90m"
        Duration.ofMillis(250)   | "250ms"
        Duration.ofMillis(0.5)   | "500us"
        Duration.ofMicros(0.5)   | "500ns"
        Duration.ofNanos(1.4)    | "1ns"
        Duration.ofDays(2)       | "2d"
        Duration.ofSeconds(-15)  | "-15s"
    }

    void "a duration too large for the short form falls back to ISO-8601"() {
        expect:
        PklValues.toDurationString(Duration.ofNanos(1e12)) == "PT16M40S"
    }

    void "data sizes become whole bytes"() {
        expect:
        PklValues.toJava(DataSize.ofMegabytes(10)) == 10_000_000L
        PklValues.toJava(DataSize.ofMebibytes(10)) == 10L * 1024 * 1024
        PklValues.toJava(DataSize.ofKibibytes(1.5)) == 1536L
    }

    void "an evaluated module converts to nested maps and lists"() {
        given:
        PModule module
        try (Evaluator evaluator = Evaluator.preconfigured()) {
            module = evaluator.evaluate(ModuleSource.text('''
                a { b = 1; c = "two"; d = 3.5; e = true }
                list = new Listing { 1; 2 }
                mapping = new Mapping { ["x"] = 1; [2] = "y" }
                pair = Pair(1, "one")
                ignored = null
                hidden h = "hidden"
                clazz = String
                re = Regex("a+")
            '''))
        }

        when:
        Map<String, Object> map = PklValues.toMap(module)

        then:
        map.a == [b: 1L, c: "two", d: 3.5d, e: true]
        map.list == [1L, 2L]
        map.mapping == [x: 1L, "2": "y"]
        map.pair == [1L, "one"]
        map.containsKey("ignored")
        map.ignored == null
        !map.containsKey("h")
        map.clazz instanceof String
        map.re instanceof String
    }
}
