# Maven → Gradle migration

This repository is the CAP Java bookshop application from
`rajeshmanikala1214/cap_java_application` with the build system migrated from
Maven to Gradle. No application source, CDS model, service definition, test or
MTA deployment topology was changed.

**Read "Verification status" before running this in a pipeline.** One step, the
`cds.gen.*` code generation, could not be executed in the environment where this
migration was prepared and needs one command to finish wiring.

---

## 1. Maven configuration removed

| Removed | Why |
|---|---|
| `pom.xml` | aggregator POM (`my:bookshop-parent`) |
| `srv/pom.xml` | `my:bookshop` module POM |
| `integration-tests/pom.xml` | `my:bookshop-integration-tests` module POM |
| `.github/workflows/maven.yml` | replaced by `.github/workflows/gradle.yml` |
| `.flattened-pom.xml` entry in `.gitignore` | flatten-maven-plugin artefact, no longer produced |

No `mvnw`, `mvnw.cmd` or `.mvn/` existed in the source repository.

## 2. Gradle configuration added

| File | Purpose |
|---|---|
| `settings.gradle` | root project name, module includes, plugin + dependency repositories |
| `gradle.properties` | every version from the old POM properties, plus Gradle-only knobs |
| `build.gradle` | root build: enforcer equivalent, Node provisioning hook |
| `gradle/java-conventions.gradle` | toolchain, compiler options, BOM imports, test, JaCoCo |
| `gradle/cds.gradle` | all six cds-maven-plugin executions |
| `gradle/node.gradle` | Node.js provisioning (`install-node` equivalent) |
| `gradle/spotless.gradle` | google-java-format check |
| `srv/build.gradle` | `:srv` dependencies and packaging |
| `integration-tests/build.gradle` | `:integration-tests`, failsafe + MTX sidecar equivalent |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Gradle wrapper 8.14.3, SHA-256 pinned |

Files edited because they referenced the Maven build (contents otherwise
unchanged): `mta.yaml`, `mta-multi-tenant.yaml`, `sonar-project.properties`,
`.github/dependabot.yml`, `.gitignore`, `README.md`.

## 3. Plugin-by-plugin conversion

| Maven plugin / config | Gradle equivalent |
|---|---|
| `spring-boot-starter-parent` 3.5.14 (parent) | `org.springframework.boot` + `io.spring.dependency-management` plugins, `spring-boot-dependencies` BOM import |
| `dependencyManagement` — `cds-services-bom` 4.9.0, `sdk-modules-bom` 5.30.0, xsuaa `java-bom` 3.7.3 | `dependencyManagement { imports { mavenBom … } }` in `gradle/java-conventions.gradle`. The `io.spring.dependency-management` plugin is used rather than Gradle `platform()` because it reproduces Maven semantics: constraints apply to every configuration |
| `maven-compiler-plugin` 3.15.0, `<release>21</release>`, UTF-8 | Java toolchain 21 + `options.release = 21`, `options.encoding = 'UTF-8'` |
| `spring-boot-maven-plugin` `repackage`, `<classifier>exec</classifier>`, `<finalName>bookshop</finalName>` | `bootJar { archiveClassifier = 'exec'; archiveVersion = '' }`, `jar { enabled = true; archiveClassifier = ''; archiveVersion = '' }`, `base.archivesName = 'bookshop'`. Produces `bookshop.jar` + `bookshop-exec.jar`, matching Maven |
| `spring-boot-maven-plugin` `<skip>true</skip>` at root | Boot plugin simply not applied outside `:srv` |
| `maven-surefire-plugin` 3.5.6, `failIfNoTests=true` | `test { useJUnitPlatform(); failOnNoDiscoveredTests = true }` |
| `maven-surefire-plugin` `failIfNoTests=false` (integration-tests) | `failOnNoDiscoveredTests = false` in that module, `*IT` excluded from `test` |
| `maven-failsafe-plugin` 3.5.6 `integration-test` + `verify` | dedicated `integrationTest` task including `**/IT*`, `**/*IT`, `**/*ITCase`, wired into `check` |
| `jacoco-maven-plugin` 0.8.12 `prepare-agent` + XML `report` at verify | `jacoco` plugin, `toolVersion = '0.8.12'`, `test.finalizedBy jacocoTestReport`, XML only |
| `flatten-maven-plugin` 1.7.3 | dropped. It existed only to resolve the CI-friendly `${revision}` placeholder, which Gradle does not need (`version` comes from `gradle.properties`) |
| `maven-enforcer-plugin` 3.6.3 (requireMavenVersion, requireJavaVersion, reactorModuleConvergence) | `verifyBuildEnvironment` task: Gradle ≥ 8.13, launcher JDK ≥ 21, group/version convergence across modules; wired into `check` |
| `spotless-maven-plugin` 3.6.0, google-java-format | `com.diffplug.spotless`, `googleJavaFormat()`, scoped to `src/main/java` + `src/test/java`. The `<pom><sortPom/></pom>` half is dropped with the POM |
| `exec-maven-plugin` 3.6.3 (async MTX sidecar start) | `startMtxSidecar` / `stopMtxSidecar` tasks around `integrationTest` |
| `native-maven-plugin` | **not carried over.** It was inherited configuration only, never bound to an execution, and `mvn verify` never invoked it. See "Deliberate omissions" |

### CDS plugin executions

| Maven execution | Gradle task | Notes |
|---|---|---|
| `cds.clean` (goal `clean`) | `cdsClean` | deletes `edmx/`, `schema-h2.sql`, `openapi.json`, `gen/`; wired into `clean` |
| `cds.install-node` (goal `install-node`) | `:installNode` | downloads Node into `./node`; `-PuseSystemNode=true` uses the agent's Node instead |
| `cds.npm-ci` (goal `npm`, args `ci`) | `npmCi` | `npm ci` at the repository root |
| `cds.resolve` (goal `resolve`) | `cdsResolve` | see below |
| `cds.build` (goal `cds`, 3 commands) | `cdsBuild` | the three commands verbatim |
| `cds.generate` (goal `generate`) | `cdsGenerate` | see section 5 |

**`cdsResolve`.** `srv/admin-service.cds` references two CDS models that ship
inside CAP Java jars rather than npm packages:

```cds
using {sap.changelog as changelog} from 'com.sap.cds/change-tracking';
using {sap.attachments.Attachments} from 'com.sap.cds/cds-feature-attachments';
```

The CDS compiler reports these as *package module* references, i.e. it resolves
them the way Node resolves packages. `cdsResolve` therefore extracts the `cds/**`
entries from the jars on `runtimeClasspath` into
`node_modules/<groupId>/<artifactId>/` and writes a minimal `package.json` so the
directory resolves as a package. It runs after `npmCi`, because `npm ci` recreates
`node_modules`. If it extracts nothing it fails with the candidate jar layouts
printed, and `-PcdsModelJarPrefix=<prefix/>` overrides the search prefix.

**`cdsBuild`** runs, with the repository root as working directory (which is where
the Maven plugin ran them — the POM's `compile srv/cat-service.cds` only resolves
from there):

1. `cds build --for java`
2. `cds deploy --to h2 --with-mocks --dry --out <srv>/src/main/resources/schema-h2.sql`
3. `cds compile srv/cat-service.cds -2 openapi --openapi:url /api/browse` → `swagger/openapi.json`

It sets `CDS_BUILD_TARGET=.`. CAP 9 defaults `cds.build.target` to `gen`, which
copies the whole module into `gen/srv/` instead of writing the model in place;
that would leave `csn.json` and `edmx/` out of `srv/src/main/resources` and hence
out of the packaged Spring Boot jar and out of reach of the code generator. The
Maven build wrote in place, which the POM confirms by passing
`${project.basedir}/src/main/resources/schema-h2.sql`.

## 4. Dependency and scope mapping

Every dependency from `srv/pom.xml` and `integration-tests/pom.xml` is carried
over one-for-one, with no version changes. Scope mapping:

| Maven | Gradle |
|---|---|
| `compile` (default) | `implementation` |
| `runtime` | `runtimeOnly` |
| `test` | `testImplementation` |
| `<optional>true</optional>` (devtools) | `developmentOnly` |
| `<exclusions>` on `my:bookshop` | `exclude group: 'ch.qos.logback', module: 'logback-classic'` on `project(':srv')` |

Two versions stay pinned outside the BOMs exactly as the POM had them:
`cds-feature-attachments` 1.5.0 and `cf-java-logging-support-*` 4.2.0.

## 5. The code-generation task

`cds:generate` produces the `cds.gen.*` typed accessor interfaces. This
application has **78 references to `cds.gen.*` across 13 files**, so generation is
mandatory to compile.

There is no Node-side or official Gradle route to it: `@sap/cds-dk` 9.9.2 has no
`generate` command and `cds compile -2` supports only
json/edmx/sql/cdl/xsuaa/openapi/asyncapi. The CAP Java documentation states the
interfaces "are generated at each build by the `cds:generate` goal of the CDS
Maven Plugin", and contains no mention of Gradle.

The generator itself, however, is a **plain library artifact**:
`com.sap.cds:cds4j-codegen` in CAP Java 4.x (renamed
`com.sap.cds:cds-services-code-generator` in 5.0, per the CAP Java migration
guide). So `cdsGenerate` resolves it into its own `cdsCodegen` configuration and
invokes it directly — no Maven, no `mvn`, no POM.

What is wired and what is not:

* resolved: `com.sap.cds:cds4j-codegen:4.9.0` on a dedicated configuration
* input: `srv/src/main/resources/edmx/csn.json` (verified to be where the CDS build puts it, 458,914 bytes for this model)
* output: `srv/build/generated/sources/cds/main/java`, registered as a source root
* incremental: task-level up-to-date on the CSN file, the generator classpath and the four settings
* ordering: `cdsBuild` → `cdsGenerate` → `compileJava`
* settings to reproduce: `basePackage=cds.gen`, `strictSetters=true`, `interfacesForAspects=true`, `linkedInterfaces=true`
* **not wired: the entry point and its argument names.** These live inside the jar, which could not be downloaded where this migration was prepared.

The task does not guess. It discovers the entry point from the artifact itself
(manifest `Main-Class`, else a class declaring `public static void main(String[])`,
preferring names containing generator/codegen/cli/main). If the invocation is not
pinned it fails with the generator's own `--help` output in the message.

### Closing it

On any machine or agent with Maven Central access:

```bash
./gradlew :srv:cdsCodegenInspect
```

That prints the resolved artifacts, the manifest `Main-Class`, every `main()`
candidate, the public API of `cds4j-codegen`, and the generator's `--help`. Then
set the invocation in `gradle.properties`:

```properties
cdsCodegenMainClass=<fully.qualified.Main>
cdsCodegenArgs=--csn {csn} --out {out} --base-package {basePackage} …
```

`{csn}`, `{out}` and `{basePackage}` are substituted with the paths above. If the
inspection shows the generator exposes only a programmatic API with no `main`, the
same output gives the constructor and method signatures needed for a small
`buildSrc` driver, which is still Maven-free.

## 6. Verification status

### Verified by execution

| What | How |
|---|---|
| Gradle wrapper | generated by Gradle 8.14.3 itself; `distributionSha256Sum` pinned so the wrapper validates its own download |
| Both modules configure without error | `gradle tasks --all` / `clean build --dry-run` on Gradle 8.14.3 with a real JDK 21 (SapMachine 21.0.7) |
| Full `clean build` task graph and ordering | dry run resolves to: clean/cdsClean → installNode → npmCi → cdsResolve → cdsBuild → cdsGenerate → compileJava → processResources → jar/bootJar → test → jacocoTestReport → npmCiSidecar → startMtxSidecar → integrationTest → stopMtxSidecar |
| `npmCi` task | executed; installed `@sap/cds-dk` 9.9.2 from `package-lock.json` |
| `cdsBuild` task, all three commands | executed through Gradle; produced `edmx/csn.json` (458,914 B), `schema-h2.sql` (54,871 B), `swagger/openapi.json` (133,950 B), no `gen/` leak |
| `cdsBuild` incremental behaviour | second invocation reports `UP-TO-DATE` |
| `cdsResolve` resolution mechanism | placing the two jar-provided models at `node_modules/com.sap.cds/<artifact>/index.cds` removes both `Can't find package module` compiler errors |

### Not verified

The environment where this migration was prepared blocks `repo1.maven.org`,
`repo.maven.apache.org`, `services.gradle.org`, `plugins.gradle.org` and
`nodejs.org` (HTTP 403, `x-deny-reason: host_not_allowed`). Consequences:

1. **`./gradlew clean build` has not been executed end to end.** No Spring Boot, CAP SDK, JaCoCo or Spotless artefact could be resolved, so nothing was compiled and no test was run. Treat the first pipeline run as the real acceptance test.
2. **`cdsGenerate` has not been executed** — see section 5. It will fail with an actionable message until `cdsCodegenArgs` is set.
3. **`cdsResolve` has not been executed against real CAP jars.** The mechanism is verified; the assumption still open is that the jars carry their models under `cds/…` or `META-INF/cds/…`. If not, the task fails and prints the actual layout, and `-PcdsModelJarPrefix` fixes it without a code change.
4. **`installNode` download path not executed** (nodejs.org blocked). All Gradle-side execution used `-PuseSystemNode=true`.
5. **Two plugin versions could not be checked against a repository**, because they have no counterpart in the POM: `io.spring.dependency-management` 1.1.7 and `com.diffplug.spotless` 7.0.4. If either fails to resolve, bump it in `gradle.properties`. Spotless can be bypassed entirely with `-PskipSpotless`, which also skips resolving it.
6. **Spotless has not been run**, so it is unknown whether the existing sources satisfy the Gradle plugin's google-java-format version. If `spotlessCheck` fails purely on formatting, run `./gradlew spotlessApply` or build with `-PskipSpotless`.
7. **`integrationTest` has not been executed.** The MTX sidecar start/stop wiring is verified only as a task graph. `-PskipIntegrationTests=true` skips it.

## 7. Deliberate omissions and differences

* **`native-maven-plugin`** is not carried over. It was inherited, unbound configuration (`metadataRepository` 0.3.14 in `srv`, `skipNativeTests` in `integration-tests`) that only activated under a `native` profile; `mvn verify` never ran it. To restore native image support, add `org.graalvm.buildtools.native` to `:srv`. It was left out to avoid making the primary build depend on a plugin version that could not be verified here.
* **CI JDK.** The old workflow ran on JDK 25 while `pom.xml` set `jdk.version=21`. The new workflow uses JDK 21, matching the toolchain. Compilation output is unchanged either way (`--release 21`).
* **JaCoCo report path** moves from `srv/target/site/jacoco/jacoco.xml` to `srv/build/reports/jacoco/test/jacocoTestReport.xml`. `sonar-project.properties` is updated accordingly.
* **Spotless ordering.** Maven bound `spotless:check` to `process-sources`, i.e. before compilation. Gradle's plugin attaches it to `check`, so a formatting violation fails later in the same build rather than earlier.
* **MTA build command.** `mvn clean package -DskipTests=true` becomes `../gradlew -p .. clean :srv:bootJar` with `build-result: build/libs/*-exec.jar`. Module topology, buildpacks, services and bindings are untouched. On a Windows MTA builder use `..\gradlew.bat`.
* **`project.exec`/`javaexec` in task actions** produce Gradle 9 deprecation warnings on 8.14.3. They work as-is; migrating to injected `ExecOperations` is the follow-up if you move to Gradle 9.

## 8. Jenkins requirements

* JDK 21 available to launch Gradle. Compilation is pinned to a Java 21 toolchain, so either the agent JDK is 21 or a JDK 21 must be discoverable by Gradle's toolchain detection.
* Network access to `repo1.maven.org` (or your mirror) and `plugins.gradle.org`.
* Network access to `services.gradle.org` for the wrapper's first run, unless you seed `GRADLE_USER_HOME/wrapper/dists` or point `distributionUrl` at an internal mirror.
* `npm` reachable at `registry.npmjs.org` for `npm ci`.
* Node.js: either allow `nodejs.org` so `installNode` can provision Node 22, or install Node 22 on the agent and build with `-PuseSystemNode=true`.
* No Maven, no `mvn`, no `pom.xml` required.

## 9. Commands

```bash
# the acceptance command
./gradlew clean build

# first run on a network-enabled agent, to pin the code generator
./gradlew :srv:cdsCodegenInspect

# recommended Jenkins invocation with Node preinstalled on the agent
./gradlew --no-daemon -PuseSystemNode=true clean build

# useful escape hatches
./gradlew clean build -PskipSpotless
./gradlew clean build -PskipIntegrationTests=true

# packaging only, as the MTA build does
./gradlew :srv:bootJar
```

Artefacts produced by a successful build:

* `srv/build/libs/bookshop.jar` — plain jar
* `srv/build/libs/bookshop-exec.jar` — executable Spring Boot jar, deployed by `mta.yaml`
* `srv/src/main/resources/edmx/`, `schema-h2.sql`, `swagger/openapi.json` — CDS build output
* `srv/build/generated/sources/cds/main/java/cds/gen/**` — generated accessor interfaces
* `srv/build/reports/jacoco/test/jacocoTestReport.xml` — coverage for SonarQube
