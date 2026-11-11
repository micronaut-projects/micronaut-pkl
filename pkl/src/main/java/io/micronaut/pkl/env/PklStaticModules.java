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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.optim.StaticOptimizations;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The flattened properties of Pkl modules that were evaluated when the application was built, keyed
 * by module path (for example {@code application.pkl} or {@code application-dev.pkl}).
 *
 * <p>The {@code pkl.to.java.config} Micronaut AOT optimization in {@code micronaut-pkl-aot} generates a
 * {@link StaticOptimizations.Loader} for this type. {@link PklPropertySourceLoader} serves a classpath
 * module found here without evaluating it, and evaluates every other module as usual.</p>
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
@Internal
public final class PklStaticModules {

    private final Map<String, Map<String, Object>> modules;

    /**
     * @param modules The flattened properties of each module, keyed by module path
     */
    public PklStaticModules(Map<String, Map<String, Object>> modules) {
        this.modules = Map.copyOf(modules);
    }

    /**
     * @param path The module path, such as {@code application.pkl}
     * @return The module's flattened properties, or {@code null} if the module was not evaluated at build time
     */
    @Nullable Map<String, Object> find(String path) {
        return modules.get(path);
    }
}
