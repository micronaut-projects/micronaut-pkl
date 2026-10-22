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

import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the configuration loaded from Pkl.
 */
@Controller("/greeting")
public class GreetingController {

    private final GreetingConfiguration configuration;
    private final long maxRequestSize;

    GreetingController(GreetingConfiguration configuration, @Value("${micronaut.server.max-request-size}") long maxRequestSize) {
        this.configuration = configuration;
        this.maxRequestSize = maxRequestSize;
    }

    @Get
    Map<String, Object> greeting() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", configuration.getMessage());
        result.put("retries", configuration.getRetries());
        result.put("timeoutSeconds", configuration.getTimeout().toSeconds());
        result.put("tags", configuration.getTags());
        result.put("hosts", configuration.getHosts());
        result.put("fromEnv", configuration.getFromEnv());
        result.put("maxRequestSize", maxRequestSize);
        return result;
    }
}
