package capgradle;

import com.sap.cds.generator.Cds4jCodegen;
import com.sap.cds.generator.ConfigurationImpl;
import com.sap.cds.generator.CsnSupplier;
import com.sap.cds.generator.Result;
import com.sap.cds.generator.util.FileSystem;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reproduces the cds-maven-plugin {@code generate} goal by driving
 * {@code com.sap.cds:cds4j-codegen} directly.
 *
 * <p>cds4j-codegen 4.9.0 exposes no {@code main(String[])} - it is a library. Its
 * entry point is {@code new Cds4jCodegen(Configuration).generate(CsnSupplier,
 * Consumer&lt;GeneratedFile&gt;)}, with {@code util.FileSystem} as the sink that
 * writes the generated files. Gradle compiles this class against the resolved
 * {@code cdsCodegen} configuration and runs it in a separate JVM, so no Maven
 * tooling and no Maven plugin API is involved.
 *
 * <p>Arguments are named, so options can be added from Gradle without touching
 * this class:
 *
 * <pre>
 *   csn=&lt;path to csn.json&gt;
 *   out=&lt;output directory&gt;
 *   set.&lt;option&gt;=&lt;value&gt;     e.g. set.basePackage=cds.gen, set.eventContext=true
 * </pre>
 *
 * <p>Each {@code set.x=y} is applied to {@link ConfigurationImpl} through its
 * {@code setX} setter, coercing to boolean, String, enum or List as the setter
 * requires. This matters because {@code ConfigurationImpl}'s own defaults are
 * plain Java defaults - notably {@code cqnServices} and {@code eventContext} are
 * {@code false}, while the Maven goal defaulted them to {@code true}. Leaving
 * them false generates entity interfaces but neither the typed service
 * interfaces nor the {@code *Context} event contexts.
 */
public final class CdsCodegenDriver {

  private static final String LOG = "[cds-codegen] ";

  private CdsCodegenDriver() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> options = new LinkedHashMap<>();
    Path csnFile = null;
    Path outputDir = null;

    for (String arg : args) {
      int eq = arg.indexOf('=');
      if (eq < 0) {
        throw new IllegalArgumentException("expected key=value, got: " + arg);
      }
      String key = arg.substring(0, eq);
      String value = arg.substring(eq + 1);
      if ("csn".equals(key)) {
        csnFile = Paths.get(value);
      } else if ("out".equals(key)) {
        outputDir = Paths.get(value);
      } else if (key.startsWith("set.")) {
        options.put(key.substring(4), value);
      } else {
        throw new IllegalArgumentException("unknown argument: " + key);
      }
    }
    if (csnFile == null || outputDir == null) {
      throw new IllegalArgumentException("csn=<file> and out=<dir> are both required");
    }

    ConfigurationImpl configuration = new ConfigurationImpl();
    for (Map.Entry<String, String> option : options.entrySet()) {
      apply(configuration, option.getKey(), option.getValue());
    }
    if (!options.containsKey("pluginInformation")) {
      configuration.setPluginInformation("cap-java gradle build (cds4j-codegen)");
    }

    logConfiguration(configuration);

    if (!Files.isRegularFile(csnFile)) {
      throw new IllegalStateException("CSN file not found: " + csnFile);
    }
    byte[] csn = Files.readAllBytes(csnFile);
    System.out.println(LOG + "read " + csn.length + " bytes of CSN from " + csnFile);

    CsnSupplier csnSupplier = () -> csn;
    Files.createDirectories(outputDir);

    // FileSystem(Path, boolean): true is the value that writes (confirmed
    // against cds4j-codegen 4.9.0). The fallback guards against the flag's
    // meaning changing in a future CAP version.
    long written = generate(configuration, csnSupplier, outputDir, true);
    if (written == 0) {
      System.out.println(LOG + "no files from FileSystem(dir, true), retrying with FileSystem(dir, false)");
      written = generate(configuration, csnSupplier, outputDir, false);
    }

    System.out.println(LOG + "generated " + written + " .java file(s) under " + outputDir);
    if (written == 0) {
      System.err.println(LOG + "the generator wrote nothing - see the status and issues above");
      System.exit(1);
    }
  }

  /** Applies one option through the matching {@code setX} setter. */
  private static void apply(ConfigurationImpl configuration, String name, String value) {
    String setterName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    Method setter = null;
    for (Method candidate : ConfigurationImpl.class.getMethods()) {
      if (candidate.getName().equals(setterName) && candidate.getParameterCount() == 1) {
        setter = candidate;
        break;
      }
    }
    if (setter == null) {
      throw new IllegalArgumentException(
          "cds4j-codegen has no configuration option '" + name + "' (looked for "
              + setterName + " on ConfigurationImpl)");
    }

    Class<?> type = setter.getParameterTypes()[0];
    Object coerced;
    if (type == boolean.class || type == Boolean.class) {
      coerced = Boolean.parseBoolean(value);
    } else if (type == String.class) {
      coerced = value;
    } else if (type.isEnum()) {
      coerced = enumConstant(type, value);
    } else if (List.class.isAssignableFrom(type)) {
      List<String> items = new ArrayList<>();
      for (String item : value.split(",")) {
        if (!item.trim().isEmpty()) {
          items.add(item.trim());
        }
      }
      coerced = items;
    } else if (type == int.class || type == Integer.class) {
      coerced = Integer.parseInt(value);
    } else {
      throw new IllegalArgumentException(
          "cannot convert '" + value + "' to " + type.getName() + " for option " + name);
    }

    try {
      setter.invoke(configuration, coerced);
    } catch (Exception e) {
      throw new IllegalStateException("failed to set " + name + "=" + value, e);
    }
  }

  private static Object enumConstant(Class<?> type, String value) {
    for (Object constant : type.getEnumConstants()) {
      if (((Enum<?>) constant).name().equalsIgnoreCase(value)) {
        return constant;
      }
    }
    throw new IllegalArgumentException(
        "'" + value + "' is not a constant of " + type.getSimpleName()
            + ", available: " + Arrays.toString(type.getEnumConstants()));
  }

  private static long generate(
      ConfigurationImpl configuration, CsnSupplier csn, Path outputDir, boolean flag)
      throws Exception {

    FileSystem sink = new FileSystem(outputDir, flag);
    Result result = new Cds4jCodegen(configuration).generate(csn, sink);

    // Result.Status is a nested type; keep it as Object so this compiles against
    // any shape of it.
    Object status = result.getStatus();
    Collection<?> issues = result.getIssues();
    System.out.println(
        LOG + "FileSystem(dir, " + flag + ") -> status=" + status
            + ", issues=" + (issues == null ? 0 : issues.size()));
    if (issues != null) {
      issues.forEach(issue -> System.out.println(LOG + "issue: " + issue));
    }

    String statusName = String.valueOf(status).toUpperCase();
    if (statusName.contains("ERROR") || statusName.contains("FAIL")) {
      throw new IllegalStateException("code generation reported status " + status);
    }
    return countJavaFiles(outputDir);
  }

  private static long countJavaFiles(Path dir) throws Exception {
    if (!Files.isDirectory(dir)) {
      return 0;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      return paths.filter(p -> p.toString().endsWith(".java")).count();
    }
  }

  /**
   * Prints the effective configuration. If an enum-typed option defaults to
   * null, its available constants are printed too, so a failure inside the
   * generator can be diagnosed from a single build log.
   */
  private static void logConfiguration(ConfigurationImpl configuration) {
    List<String> lines = new ArrayList<>();
    for (Method method : ConfigurationImpl.class.getMethods()) {
      if (method.getParameterCount() != 0
          || method.getDeclaringClass() == Object.class
          || !(method.getName().startsWith("get") || method.getName().startsWith("is"))) {
        continue;
      }
      try {
        Object value = method.invoke(configuration);
        StringBuilder line = new StringBuilder(LOG + "config " + method.getName() + " = " + value);
        if (value == null && method.getReturnType().isEnum()) {
          line.append("  WARNING null enum, available constants: ")
              .append(Arrays.toString(method.getReturnType().getEnumConstants()));
        }
        lines.add(line.toString());
      } catch (Exception e) {
        lines.add(LOG + "config " + method.getName() + " threw " + e);
      }
    }
    lines.sort(String::compareTo);
    lines.forEach(System.out::println);
  }
}
