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
package io.micronaut.pkl.generator;

import io.micronaut.core.annotation.Internal;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Naming helpers for generated Pkl code.
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
@Internal
final class PklIdentifiers {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]+");

    /**
     * Pkl keywords and reserved words that cannot be used as bare identifiers.
     */
    private static final Set<String> KEYWORDS = Set.of(
        "abstract", "amends", "as", "case", "class", "const", "delete", "else", "extends", "external", "false",
        "fixed", "for", "function", "hidden", "if", "import", "in", "is", "let", "local", "module", "new", "nothing",
        "null", "open", "out", "outer", "override", "protected", "read", "record", "super", "switch", "this",
        "throw", "trace", "true", "typealias", "unknown", "vararg", "when", "default"
    );

    private PklIdentifiers() {
    }

    /**
     * Converts a Micronaut property name (kebab-case or camelCase) into a Pkl property identifier,
     * quoting it with backticks when it is a keyword or not a valid identifier.
     *
     * @param propertyName The property name
     * @return The identifier
     */
    static String property(String propertyName) {
        String camel = camelCase(propertyName);
        if (IDENTIFIER.matcher(camel).matches() && !KEYWORDS.contains(camel)) {
            return camel;
        }
        return "`" + camel + "`";
    }

    /**
     * Converts kebab-case to camelCase, leaving other characters untouched.
     *
     * @param name The name
     * @return The camelCase form
     */
    static String camelCase(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean upper = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-' && i > 0 && i < name.length() - 1) {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Converts a path segment such as {@code http-client} into a type-name fragment such as {@code HttpClient}.
     *
     * @param segment The segment
     * @return The fragment
     */
    static String typeFragment(String segment) {
        StringBuilder sb = new StringBuilder();
        for (String part : NON_ALPHANUMERIC.split(segment)) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part, 1, part.length());
            }
        }
        if (sb.isEmpty() || !Character.isLetter(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
