# Repository Guidance

Micronaut Pkl lets a Micronaut application be configured with [Apple Pkl](https://pkl-lang.org) modules. Keep this file short and update it when the repository layout changes.

## Repository Shape

- `pkl/` is the `micronaut-pkl` module: `io.micronaut.pkl.env.PklPropertySourceLoader` (registered in `META-INF/services`), `PklValues` (Pkl value to property value conversion) and `PklLoaderSettings` (system property and environment variable settings read before the `Environment` exists).
- `pkl-generator/` is `micronaut-pkl-generator`: `SchemaLocator` finds the JSON schemas Micronaut packages under `META-INF/micronaut-configuration-schemas`, `PklSchemaGenerator` turns them into a typed Pkl module (one `open class` per prefix, `Mapping` for `@EachProperty`, constraints and doc comments), `PklSchemaGeneratorCli` is the command line entry point.
- `test-suite-graal/` is a small Netty application configured only by `application.pkl`; `./gradlew :test-suite-graal:nativeTest` builds it as a GraalVM native image (the graalvm CI workflows run it) and checks that Pkl evaluation works inside the image. It pins the GraalVM polyglot artifacts to `libs.versions.graal`, which must be at least the GraalVM version used to build.
- `pkl/src/main/resources/META-INF/native-image/` holds the native-image metadata for pkl-core: node classes in `org.pkl.core.ast` and `org.pkl.core.stdlib` are initialised at build time (Truffle requires it), the rest of pkl-core at run time (its standard-library holders evaluate Pkl in static initialisers, which captures build threads in the image heap), plus the few runtime classes whose constants the node classes embed. When a pkl-core upgrade reports a new runtime type found in the image heap, add that class to the build-time list.
- `pkl-gradle-plugin/` (`io.micronaut.pkl`, `generatePklSchema` task, `micronautPkl` extension) and `pkl-maven-plugin/` (`generate-pkl-schema` goal, hand-written `META-INF/maven/plugin.xml`) run the generator in builds and add the module to the application resources. The Gradle plugin is tested with TestKit against Maven Central, the mojo with the Maven plugin testing harness.
- `pkl-bom/` is the BOM. Keep dependency-management changes separate from module implementation changes when possible.
- `buildSrc/src/main/groovy/io.micronaut.build.internal.pkl-*.gradle` holds the convention plugins.
- `gradle/libs.versions.toml` pins the `org.pkl-lang` artifacts under `managed-pkl`; the module depends on the unshaded `pkl-core` so that the Truffle runtime is shared with other Truffle users on the classpath.
- `.agents/skills/` is shared agent guidance synced from micronaut-project-template.

## Design Rules

- The loader implements `PropertySourceLoader` directly, not `AbstractPropertySourceLoader`, because the environment reads abstract loaders from an `InputStream` and the module's location is needed for relative `amends` and `import` clauses.
- An environment module without an `amends`/`extends` clause implicitly amends `application.pkl`; keep that behaviour and its documentation in sync.
- Evaluation is sandboxed by default (`pkl:`, `modulepath:` modules; `env:`, `prop:`, `modulepath:` resources; `file:` only below a file-system module's directory). Widening a default is a user-visible change and belongs in the guide.
- Pkl `Duration` values become Micronaut's short string form (`30s`); `DataSize` values become whole bytes. Do not emit unit strings for data sizes: Pkl's `mb` is decimal while `@ReadableBytes` is binary.

## Contributing Guidelines

- Before opening or updating a pull request, read `CONTRIBUTING.md` and follow every repo-specific PR requirement it names.
- Treat contributor-checklist items as handoff requirements. If a requirement is not applicable, state that explicitly in the PR description.

## Documentation

- User guide sources live in `src/main/docs/guide`, with navigation in `src/main/docs/guide/toc.yml`.
- Build guide output with `./gradlew publishGuide` or `./gradlew pG`; build guide plus Javadocs with `./gradlew docs`.
- Release-note behavior is maintained through `.github/release.yml`, `.github/workflows/release.yml`, and the release process documented in `MAINTAINING.md`.

## Verification

- Use `./gradlew check` for general validation and `./gradlew :micronaut-pkl:test` for the module's Spock specs.
- Use `./gradlew publishGuide` after guide or `toc.yml` changes.
- Use `./gradlew docs` when API docs or release documentation output matters.
