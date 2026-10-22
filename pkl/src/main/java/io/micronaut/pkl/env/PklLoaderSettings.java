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

import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Settings for the Pkl loader. These are read from system properties and environment
 * variables only, because the loader runs before the {@link io.micronaut.context.env.Environment}
 * exists.
 *
 * @param enabled          Whether the loader is enabled
 * @param timeout          The evaluation timeout
 * @param allowedModules   Extra allowed module URI patterns, or {@code null} for the defaults
 * @param allowedResources Extra allowed resource URI patterns, or {@code null} for the defaults
 * @author Graeme Rocher
 * @since 1.0.0
 */
@Internal
record PklLoaderSettings(
    boolean enabled,
    Duration timeout,
    @Nullable List<Pattern> allowedModules,
    @Nullable List<Pattern> allowedResources) {

    /**
     * The property prefix.
     */
    static final String PREFIX = "micronaut.config.pkl.";

    /**
     * The default evaluation timeout.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern DURATION = Pattern.compile("^(\\d+)(ms|s|m|h)?$");

    /**
     * Reads the settings from system properties, falling back to environment variables
     * (for example {@code MICRONAUT_CONFIG_PKL_TIMEOUT}).
     *
     * @return The settings
     */
    static PklLoaderSettings load() {
        return load(System::getProperty, System::getenv);
    }

    /**
     * Reads the settings from the given lookups.
     *
     * @param properties  The system property lookup
     * @param environment The environment variable lookup
     * @return The settings
     */
    static PklLoaderSettings load(Function<String, @Nullable String> properties, Function<String, @Nullable String> environment) {
        return new PklLoaderSettings(
            !"false".equalsIgnoreCase(value("enabled", properties, environment)),
            parseTimeout(value("timeout", properties, environment)),
            patterns("allowed-modules", value("allowed-modules", properties, environment)),
            patterns("allowed-resources", value("allowed-resources", properties, environment))
        );
    }

    private static @Nullable String value(String name, Function<String, @Nullable String> properties, Function<String, @Nullable String> environment) {
        String value = properties.apply(PREFIX + name);
        if (StringUtils.isEmpty(value)) {
            value = environment.apply((PREFIX + name).toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        }
        return StringUtils.isEmpty(value) ? null : value.trim();
    }

    private static Duration parseTimeout(@Nullable String value) {
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        var matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw new ConfigurationException("Invalid value for " + PREFIX + "timeout [" + value + "]: expected a number with an optional ms, s, m or h suffix");
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        if (unit == null) {
            unit = "s";
        }
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofSeconds(amount);
        };
    }

    private static @Nullable List<Pattern> patterns(String name, @Nullable String value) {
        if (value == null) {
            return null;
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String pattern : value.split(",")) {
            if (StringUtils.hasText(pattern)) {
                try {
                    patterns.add(Pattern.compile(pattern.trim()));
                } catch (PatternSyntaxException e) {
                    throw new ConfigurationException("Invalid regular expression [" + pattern.trim() + "] in " + PREFIX + name + ": " + e.getDescription(), e);
                }
            }
        }
        return patterns;
    }
}
