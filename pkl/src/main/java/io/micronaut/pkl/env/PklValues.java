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

import org.jspecify.annotations.Nullable;
import org.pkl.core.DataSize;
import org.pkl.core.Duration;
import org.pkl.core.DurationUnit;
import org.pkl.core.PModule;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts the values of an evaluated Pkl module into the plain Java values a
 * Micronaut {@link io.micronaut.context.env.PropertySource} holds.
 *
 * <p>Objects and mappings become {@link Map}s, listings become {@link List}s, a
 * {@link Duration} becomes the short string form Micronaut's conversion service
 * parses (for example {@code 30s} or {@code 5m}) and a {@link DataSize} becomes its
 * exact number of bytes. {@code null} values are kept so that nested maps are flattened
 * consistently; hidden members never reach this class because Pkl omits them from the
 * exported object.</p>
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
public final class PklValues {

    private PklValues() {
    }

    /**
     * Converts an evaluated module into nested maps of plain Java values.
     *
     * @param module The module
     * @return The module's properties, converted
     */
    public static Map<String, Object> toMap(PModule module) {
        return toMap(module.getProperties());
    }

    /**
     * Flattens nested maps into dotted property keys, in the same way the other Micronaut
     * configuration loaders do: a non-empty map becomes a prefix, anything else a value.
     *
     * @param nested The nested maps
     * @return The flattened properties
     */
    public static Map<String, Object> flatten(Map<String, Object> nested) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten(flat, nested, "");
        return flat;
    }

    /**
     * Converts a single Pkl value into a plain Java value.
     *
     * @param value The Pkl value, may be {@code null}
     * @return The converted value, or {@code null}
     */
    public static @Nullable Object toJava(@Nullable Object value) {
        if (value == null || value instanceof PNull) {
            return null;
        }
        if (value instanceof PObject object) {
            return toMap(object.getProperties());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = LinkedHashMap.newLinkedHashMap(map.size());
            map.forEach((k, v) -> converted.put(String.valueOf(k), toJava(v)));
            return converted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(PklValues::toJava).collect(Collectors.toCollection(ArrayList::new));
        }
        if (value instanceof Pair<?, ?> pair) {
            List<Object> converted = new ArrayList<>(2);
            converted.add(toJava(pair.getFirst()));
            converted.add(toJava(pair.getSecond()));
            return converted;
        }
        if (value instanceof Duration duration) {
            return toDurationString(duration);
        }
        if (value instanceof DataSize dataSize) {
            return dataSize.inWholeBytes();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
            return value;
        }
        return value.toString();
    }

    /**
     * Renders a Pkl duration in the short form Micronaut's conversion service accepts:
     * an integer followed by {@code ns}, {@code us}, {@code ms}, {@code s}, {@code m},
     * {@code h} or {@code d}. A fractional amount is expressed in the next smaller unit
     * until it is whole; an amount that does not fit the short form falls back to the
     * ISO-8601 representation, which Micronaut also parses.
     *
     * @param duration The duration
     * @return The string form
     */
    public static String toDurationString(Duration duration) {
        DurationUnit unit = duration.getUnit();
        double value = duration.getValue();
        while (unit != DurationUnit.NANOS && value != Math.rint(value)) {
            DurationUnit smaller = smaller(unit);
            value = duration.convertValueTo(smaller);
            unit = smaller;
        }
        long whole = Math.round(value);
        if (whole > Integer.MAX_VALUE || whole < Integer.MIN_VALUE) {
            return duration.toIsoString();
        }
        return whole + micronautSuffix(unit);
    }

    private static Map<String, Object> toMap(Map<String, Object> properties) {
        Map<String, Object> converted = LinkedHashMap.newLinkedHashMap(properties.size());
        properties.forEach((k, v) -> converted.put(k, toJava(v)));
        return converted;
    }

    private static void flatten(Map<String, Object> flat, Map<String, Object> nested, String prefix) {
        nested.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) map;
                flatten(flat, child, prefix + key + '.');
            } else {
                flat.put(prefix + key, value);
            }
        });
    }

    private static DurationUnit smaller(DurationUnit unit) {
        return switch (unit) {
            case DAYS -> DurationUnit.HOURS;
            case HOURS -> DurationUnit.MINUTES;
            case MINUTES -> DurationUnit.SECONDS;
            case SECONDS -> DurationUnit.MILLIS;
            case MILLIS -> DurationUnit.MICROS;
            case MICROS, NANOS -> DurationUnit.NANOS;
        };
    }

    private static String micronautSuffix(DurationUnit unit) {
        return switch (unit) {
            case NANOS -> "ns";
            case MICROS -> "us";
            case MILLIS -> "ms";
            case SECONDS -> "s";
            case MINUTES -> "m";
            case HOURS -> "h";
            case DAYS -> "d";
        };
    }
}
