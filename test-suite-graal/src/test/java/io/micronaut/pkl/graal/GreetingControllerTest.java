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
package io.micronaut.pkl.graal;

import io.micronaut.context.env.Environment;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@MicronautTest
class GreetingControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    Environment environment;

    @Inject
    GreetingConfiguration configuration;

    @Test
    void configurationIsLoadedFromPkl() {
        // application-test.pkl in src/test/resources implicitly extends the open application.pkl in src/main/resources
        assertEquals("Hello from pkl-graal (test)", configuration.getMessage());
        assertEquals(5, configuration.getRetries());
        assertEquals(Duration.ofSeconds(90), configuration.getTimeout());
        assertEquals(List.of("native", "pkl"), configuration.getTags());
        assertEquals(Map.of("primary", "a", "secondary", "b"), configuration.getHosts());
        assertEquals("unset", configuration.getFromEnv());
        // a key that only the test module defines, allowed because the production module is open
        assertEquals(Boolean.TRUE, environment.getRequiredProperty("test-only.enabled", Boolean.class));
        assertEquals("pkl-graal", environment.getRequiredProperty("micronaut.application.name", String.class));
        assertEquals("30s", environment.getRequiredProperty("micronaut.server.idle-timeout", String.class));
        assertFalse(environment.containsProperty("micronaut.server.nothing"));
    }

    @Test
    void controllerSeesPklValues() {
        Map<String, Object> result = client.toBlocking().retrieve(HttpRequest.GET("/greeting"), Argument.mapOf(String.class, Object.class));
        assertEquals("Hello from pkl-graal (test)", result.get("message"));
        assertEquals(5, ((Number) result.get("retries")).intValue());
        assertEquals(90, ((Number) result.get("timeoutSeconds")).intValue());
        assertEquals(10L * 1024 * 1024, ((Number) result.get("maxRequestSize")).longValue());
    }
}
