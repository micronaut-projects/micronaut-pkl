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

import io.micronaut.json.tree.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the JSON configuration schemas Micronaut packages with each module into a single Pkl
 * module that describes every known configuration property as a typed class tree.
 *
 * <p>Each {@code @ConfigurationProperties} schema becomes an {@code open class} placed at its
 * prefix, so {@code micronaut.server.netty} yields {@code MicronautConfig}, {@code MicronautServerConfig}
 * and {@code MicronautServerNettyConfig}. An {@code @EachProperty} schema becomes a
 * {@code Mapping<String, ...Entry>} (or a {@code Listing} for list containers). Every property is
 * nullable, so a generated module imposes no values of its own; defaults, Java types and
 * deprecations are carried as doc comments and {@code @Deprecated} annotations, and
 * validation constraints become Pkl type constraints.</p>
 *
 * <p>An application amends the generated module to get completion and validation:</p>
 * <pre>
 * amends "Micronaut.pkl"
 *
 * micronaut {
 *   server {
 *     port = 8080
 *   }
 * }
 * </pre>
 *
 * @author Graeme Rocher
 * @since 1.0.0
 */
public final class PklSchemaGenerator {

    /**
     * The default module name.
     */
    public static final String DEFAULT_MODULE_NAME = "Micronaut";

    private static final String INDENT = "  ";
    private static final Pattern CODE_TAG = Pattern.compile("\\{@(?:code|literal) ([^}]*)}");
    private static final Pattern LINK_TAG = Pattern.compile("\\{@link(?:plain)? ([^}\\s]*)(?: ([^}]*))?}");
    private static final Pattern VALUE_TAG = Pattern.compile("\\{@value #?([^}]*)}");
    private static final Pattern PARAGRAPH_TAG = Pattern.compile("(?i)<p>|<br\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final String moduleName;
    private final Node root = new Node(null, "");
    private final Map<String, Node> classNames = new HashMap<>();

    private PklSchemaGenerator(String moduleName) {
        this.moduleName = moduleName;
    }

    /**
     * Generates a Pkl module from the given schemas.
     *
     * @param schemas    The parsed configuration schemas
     * @param moduleName The name of the generated module, for example {@code Micronaut}
     * @return The Pkl source
     */
    public static String generate(Collection<JsonNode> schemas, String moduleName) {
        PklSchemaGenerator generator = new PklSchemaGenerator(moduleName);
        for (JsonNode schema : schemas) {
            generator.add(schema);
        }
        return generator.render();
    }

    /**
     * Generates a Pkl module named {@value #DEFAULT_MODULE_NAME} from the given schemas.
     *
     * @param schemas The parsed configuration schemas
     * @return The Pkl source
     */
    public static String generate(Collection<JsonNode> schemas) {
        return generate(schemas, DEFAULT_MODULE_NAME);
    }

    private void add(JsonNode schema) {
        JsonNode micronaut = schema.get("x-micronaut");
        if (micronaut == null || !micronaut.isObject()) {
            return;
        }
        String prefix = string(micronaut, "prefix");
        if (prefix == null || prefix.isEmpty()) {
            return;
        }
        Node node = nodeFor(prefix);
        boolean eachProperty = "each-property".equals(string(micronaut, "kind"));
        if (eachProperty) {
            boolean list = "list".equals(string(micronaut, "container"));
            Node entry = node.entry(list);
            JsonNode defs = schema.get("$defs");
            JsonNode entrySchema = defs != null ? defs.get("Entry") : null;
            if (entrySchema != null) {
                apply(entry, entrySchema, string(schema, "description"), string(micronaut, "type"));
            }
        } else {
            apply(node, schema, string(schema, "description"), string(micronaut, "type"));
        }
    }

    private Node nodeFor(String prefix) {
        Node node = root;
        for (String segment : prefix.split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            node = "*".equals(segment) ? node.entry(false) : node.child(segment);
        }
        return node;
    }

    private void apply(Node node, JsonNode objectSchema, @Nullable String description, @Nullable String sourceType) {
        if (description != null && node.doc == null) {
            node.doc = description;
        }
        if (sourceType != null) {
            node.sourceTypes.add(sourceType);
        }
        JsonNode properties = objectSchema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : properties.entries()) {
            String name = entry.getKey();
            JsonNode propertySchema = entry.getValue();
            String type = string(propertySchema, "type");
            JsonNode nested = propertySchema.get("properties");
            if ("*".equals(name) || ("object".equals(type) && nested != null && nested.isObject())) {
                // the schema writer inlines nested @EachProperty beans as a property named "*"
                Node target = "*".equals(name) ? node.entry(false) : node.child(name);
                apply(target, propertySchema, string(propertySchema, "description"), string(propertySchema, "x-micronaut-sourceType"));
            } else if ("array".equals(type) && hasObjectItems(propertySchema)) {
                Node entryNode = node.child(name).entry(true);
                apply(entryNode, propertySchema.get("items"), string(propertySchema, "description"), string(propertySchema, "x-micronaut-sourceType"));
            } else {
                node.properties.putIfAbsent(name, propertySchema);
            }
        }
    }

    private static boolean hasObjectItems(JsonNode arraySchema) {
        JsonNode items = arraySchema.get("items");
        return items != null && items.isObject() && "object".equals(string(items, "type")) && items.get("properties") != null;
    }

    // ----- rendering -----

    private String render() {
        assignClassNames(root);
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("/// Micronaut configuration schema generated by micronaut-pkl from the configuration JSON schemas\n");
        out.append("/// of the modules on the classpath. Do not edit; regenerate when dependencies change.\n");
        out.append("///\n");
        out.append("/// Amend this module from `application.pkl` to get completion and validation:\n");
        out.append("///\n");
        out.append("/// ```\n");
        out.append("/// amends \"").append(moduleName).append(".pkl\"\n");
        out.append("/// ```\n");
        out.append("open module ").append(moduleName).append("\n\n");
        renderMembers(out, root, "", null);
        List<Node> classes = new ArrayList<>();
        collectClasses(root, classes);
        for (Node node : classes) {
            out.append('\n');
            renderClass(out, node);
        }
        return out.toString();
    }

    private void assignClassNames(Node node) {
        for (Node child : node.children.values()) {
            assignClassName(child);
            assignClassNames(child);
        }
        if (node.entry != null) {
            assignClassName(node.entry);
            assignClassNames(node.entry);
        }
    }

    private void assignClassName(Node node) {
        if (node.isLeafContainer()) {
            return;
        }
        String base = node.typeName();
        String name = base;
        int suffix = 2;
        while (classNames.containsKey(name)) {
            name = base + suffix++;
        }
        classNames.put(name, node);
        node.className = name;
    }

    private void collectClasses(Node node, List<Node> classes) {
        for (Node child : node.children.values()) {
            if (child.className != null) {
                classes.add(child);
            }
            collectClasses(child, classes);
        }
        if (node.entry != null) {
            if (node.entry.className != null) {
                classes.add(node.entry);
            }
            collectClasses(node.entry, classes);
        }
    }

    private void renderClass(StringBuilder out, Node node) {
        renderDoc(out, classDoc(node), "");
        out.append("open class ").append(node.className);
        Node base = node.inheritedEntry();
        if (base != null) {
            // a named key of an @EachProperty prefix also accepts every property of the entry type
            out.append(" extends ").append(base.className);
        }
        out.append(" {\n");
        renderMembers(out, node, INDENT, base);
        out.append("}\n");
    }

    private void renderMembers(StringBuilder out, Node node, String indent, @Nullable Node inherited) {
        Map<String, Runnable> members = new TreeMap<>();
        for (Map.Entry<String, Node> child : node.children.entrySet()) {
            if (inherited == null || !inherited.declares(child.getKey())) {
                members.put(child.getKey(), () -> renderChild(out, child.getKey(), child.getValue(), indent));
            }
        }
        for (Map.Entry<String, JsonNode> property : node.properties.entrySet()) {
            if (inherited == null || !inherited.declares(property.getKey())) {
                members.putIfAbsent(property.getKey(), () -> renderProperty(out, property.getKey(), property.getValue(), indent));
            }
        }
        boolean first = true;
        for (Runnable member : members.values()) {
            if (!first) {
                out.append('\n');
            }
            member.run();
            first = false;
        }
    }

    private void renderChild(StringBuilder out, String name, Node child, String indent) {
        renderDoc(out, cleanDoc(child.doc), indent);
        out.append(indent).append(PklIdentifiers.property(name)).append(": ").append(child.typeReference()).append(" = new {}\n");
    }

    private void renderProperty(StringBuilder out, String name, JsonNode schema, String indent) {
        StringBuilder doc = new StringBuilder();
        String description = cleanDoc(string(schema, "description"));
        if (description != null) {
            doc.append(description);
        }
        JsonNode defaultValue = schema.get("default");
        if (defaultValue != null && !defaultValue.isNull()) {
            doc.append(doc.isEmpty() ? "" : "\n\n").append("Default: `").append(defaultValue.getValue()).append('`');
        }
        String javaType = string(schema, "x-micronaut-javaType");
        if (javaType != null) {
            doc.append(doc.isEmpty() ? "" : "\n\n").append("Java type: `").append(javaType).append('`');
        }
        renderDoc(out, doc.isEmpty() ? null : doc.toString(), indent);
        JsonNode deprecated = schema.get("deprecated");
        if (deprecated != null && deprecated.isBoolean() && deprecated.getBooleanValue()) {
            out.append(indent).append("@Deprecated\n");
        }
        out.append(indent).append(PklIdentifiers.property(name)).append(": ").append(pklType(schema)).append("?\n");
    }

    private static String pklType(JsonNode schema) {
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && enumValues.size() > 0) {
            StringBuilder union = new StringBuilder("(");
            boolean first = true;
            for (JsonNode value : enumValues.values()) {
                if (!first) {
                    union.append(" | ");
                }
                union.append(pklString(String.valueOf(value.getValue())));
                first = false;
            }
            return union.append(')').toString();
        }
        String type = string(schema, "type");
        String format = string(schema, "format");
        if (type == null) {
            return "Any";
        }
        return switch (type) {
            case "integer" -> constrained("Int", numericConstraints(schema));
            case "number" -> constrained("Float", numericConstraints(schema));
            case "boolean" -> "Boolean";
            case "string" -> "duration".equals(format) ? "Duration" : constrained("String", stringConstraints(schema));
            case "array" -> constrained("Listing<" + itemType(schema) + ">", sizeConstraints(schema, "minItems", "maxItems"));
            case "object" -> constrained("Mapping<String, " + additionalPropertiesType(schema) + ">", sizeConstraints(schema, "minProperties", "maxProperties"));
            default -> "Any";
        };
    }

    private static String additionalPropertiesType(JsonNode objectSchema) {
        JsonNode additional = objectSchema.get("additionalProperties");
        if (additional == null || !additional.isObject() || string(additional, "type") == null) {
            return "Any";
        }
        String type = pklType(additional);
        return type.startsWith("(") ? type : type.replaceAll("\\(.*\\)$", "");
    }

    private static String itemType(JsonNode arraySchema) {
        JsonNode items = arraySchema.get("items");
        if (items == null || !items.isObject()) {
            return "Any";
        }
        String type = pklType(items);
        return type.startsWith("(") ? type : type.replaceAll("\\(.*\\)$", "");
    }

    private static List<String> numericConstraints(JsonNode schema) {
        List<String> constraints = new ArrayList<>(2);
        JsonNode min = schema.get("minimum");
        JsonNode exclusiveMin = schema.get("exclusiveMinimum");
        JsonNode max = schema.get("maximum");
        JsonNode exclusiveMax = schema.get("exclusiveMaximum");
        if (min != null && min.isNumber() && max != null && max.isNumber()) {
            constraints.add("isBetween(" + min.getValue() + ", " + max.getValue() + ")");
        } else {
            if (min != null && min.isNumber()) {
                constraints.add("this >= " + min.getValue());
            }
            if (max != null && max.isNumber()) {
                constraints.add("this <= " + max.getValue());
            }
        }
        if (exclusiveMin != null && exclusiveMin.isNumber()) {
            constraints.add("this > " + exclusiveMin.getValue());
        }
        if (exclusiveMax != null && exclusiveMax.isNumber()) {
            constraints.add("this < " + exclusiveMax.getValue());
        }
        return constraints;
    }

    private static List<String> stringConstraints(JsonNode schema) {
        List<String> constraints = sizeConstraints(schema, "minLength", "maxLength");
        String pattern = string(schema, "pattern");
        if (pattern != null && !pattern.isEmpty() && !pattern.contains("\"#")) {
            constraints.add("matches(Regex(#\"" + pattern + "\"#))");
        }
        return constraints;
    }

    private static List<String> sizeConstraints(JsonNode schema, String minKey, String maxKey) {
        List<String> constraints = new ArrayList<>(2);
        JsonNode min = schema.get(minKey);
        JsonNode max = schema.get(maxKey);
        if (min != null && min.isNumber()) {
            constraints.add("length >= " + min.getValue());
        }
        if (max != null && max.isNumber()) {
            constraints.add("length <= " + max.getValue());
        }
        return constraints;
    }

    private static String constrained(String type, List<String> constraints) {
        if (constraints.isEmpty()) {
            return type;
        }
        return type + "(" + String.join(" && ", constraints) + ")";
    }

    private static String pklString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static @Nullable String classDoc(Node node) {
        StringBuilder doc = new StringBuilder();
        String description = cleanDoc(node.doc);
        if (description != null) {
            doc.append(description);
        }
        if (!node.sourceTypes.isEmpty()) {
            doc.append(doc.isEmpty() ? "" : "\n\n").append("Configuration prefix: `").append(node.path()).append("`");
            for (String sourceType : node.sourceTypes) {
                doc.append("\n\nJava type: `").append(sourceType).append('`');
            }
        }
        return doc.isEmpty() ? null : doc.toString();
    }

    private static void renderDoc(StringBuilder out, @Nullable String doc, String indent) {
        if (doc == null) {
            return;
        }
        for (String line : doc.split("\n")) {
            out.append(indent).append("///");
            if (!line.isEmpty()) {
                out.append(' ').append(line);
            }
            out.append('\n');
        }
    }

    /**
     * Converts a Javadoc description into Markdown suitable for a Pkl doc comment.
     *
     * @param javadoc The description, may be {@code null}
     * @return The cleaned text, or {@code null} if empty
     */
    static @Nullable String cleanDoc(@Nullable String javadoc) {
        if (javadoc == null) {
            return null;
        }
        String text = javadoc;
        text = replace(CODE_TAG, text, m -> "`" + m.group(1).trim() + "`");
        text = replace(LINK_TAG, text, m -> {
            String label = m.group(2);
            if (label != null && !label.isBlank()) {
                return label.trim();
            }
            String target = m.group(1);
            int hash = target.indexOf('#');
            String member = hash >= 0 ? target.substring(hash + 1) : null;
            String type = hash >= 0 ? target.substring(0, hash) : target;
            String simple = type.substring(type.lastIndexOf('.') + 1);
            String ref = member == null ? simple : (simple.isEmpty() ? member : simple + "." + member);
            return "`" + ref + "`";
        });
        text = replace(VALUE_TAG, text, m -> "`" + m.group(1).trim() + "`");
        text = PARAGRAPH_TAG.matcher(text).replaceAll("\n\n");
        text = HTML_TAG.matcher(text).replaceAll("");
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n")) {
            sb.append(line.strip()).append('\n');
        }
        text = BLANK_LINES.matcher(sb.toString()).replaceAll("\n\n").strip();
        return text.isEmpty() ? null : text;
    }

    private static String replace(Pattern pattern, String text, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static @Nullable String string(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isString() ? value.getStringValue() : null;
    }

    /**
     * A node of the prefix tree: either an object with properties and children, or a container
     * (mapping or listing) whose entries are described by {@link #entry}.
     */
    private static final class Node {
        final @Nullable Node parent;
        final String name;
        final Map<String, Node> children = new TreeMap<>();
        final Map<String, JsonNode> properties = new TreeMap<>();
        final Set<String> sourceTypes = new LinkedHashSet<>();
        @Nullable Node entry;
        boolean list;
        @Nullable String doc;
        @Nullable String className;

        Node(@Nullable Node parent, String name) {
            this.parent = parent;
            this.name = name;
        }

        Node child(String childName) {
            return children.computeIfAbsent(childName, n -> new Node(this, n));
        }

        Node entry(boolean listContainer) {
            if (entry == null) {
                entry = new Node(this, "*");
                list = listContainer;
            }
            return entry;
        }

        /**
         * @return Whether this node only wraps entries and declares no members of its own
         */
        boolean isLeafContainer() {
            return entry != null && children.isEmpty() && properties.isEmpty();
        }

        /**
         * @return The entry type of the parent container when this node is a named key of an
         * {@code @EachProperty} prefix, so that the generated class can extend it
         */
        @Nullable Node inheritedEntry() {
            if (parent == null || parent.entry == null || parent.entry == this || parent.entry.className == null) {
                return null;
            }
            return parent.entry;
        }

        boolean declares(String member) {
            return children.containsKey(member) || properties.containsKey(member);
        }

        String typeReference() {
            if (isLeafContainer()) {
                String entryType = entry.className != null ? entry.className : "Dynamic";
                return list ? "Listing<" + entryType + ">" : "Mapping<String, " + entryType + ">";
            }
            return className != null ? className : "Dynamic";
        }

        String path() {
            if (parent == null || parent.parent == null) {
                return name;
            }
            return parent.path() + "." + name;
        }

        String typeName() {
            StringBuilder sb = new StringBuilder();
            Node node = this;
            List<Node> chain = new ArrayList<>();
            while (node != null && node.parent != null) {
                chain.add(0, node);
                node = node.parent;
            }
            for (Node element : chain) {
                sb.append("*".equals(element.name) ? "Entry" : PklIdentifiers.typeFragment(element.name));
            }
            if (!"*".equals(name)) {
                sb.append("Config");
            }
            return sb.toString();
        }
    }
}
