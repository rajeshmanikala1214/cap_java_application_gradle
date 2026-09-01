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
import java.util.Arrays;
import java.util.Collection;
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
 * <p>Arguments, in order: csn file, output directory, base package,
 * strictSetters, interfacesForAspects, linkedInterfaces. They mirror the four
 * options the original {@code srv/pom.xml} configured on the goal.
 */
public final class CdsCodegenDriver {

  private static final String LOG = "[cds-codegen] ";

  private CdsCodegenDriver() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 6) {
      System.err.println(
          "usage: CdsCodegenDriver <csnFile> <outputDir> <basePackage>"
              + " <strictSetters> <interfacesForAspects> <linkedInterfaces>");
      System.exit(2);
    }

    Path csnFile = Paths.get(args[0]);
    Path outputDir = Paths.get(args[1]);

    ConfigurationImpl configuration = new ConfigurationImpl();
    configuration.setBasePackage(args[2]);
    configuration.setStrictSetters(Boolean.parseBoolean(args[3]));
    configuration.setInterfacesForAspects(Boolean.parseBoolean(args[4]));
    configuration.setLinkedInterfaces(Boolean.parseBoolean(args[5]));
    configuration.setPluginInformation("cap-java gradle build (cds4j-codegen)");

    logConfiguration(configuration);

    if (!Files.isRegularFile(csnFile)) {
      throw new IllegalStateException("CSN file not found: " + csnFile);
    }
    byte[] csn = Files.readAllBytes(csnFile);
    System.out.println(LOG + "read " + csn.length + " bytes of CSN from " + csnFile);

    CsnSupplier csnSupplier = () -> csn;
    Files.createDirectories(outputDir);

    // FileSystem(Path, boolean) - the flag's meaning is not documented publicly,
    // so try it one way and fall back if nothing was written. Whichever attempt
    // produces files is reported, so the behaviour is never silent.
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
    for (Method method : ConfigurationImpl.class.getMethods()) {
      if (method.getParameterCount() != 0
          || method.getDeclaringClass() == Object.class
          || !(method.getName().startsWith("get") || method.getName().startsWith("is"))) {
        continue;
      }
      try {
        Object value = method.invoke(configuration);
        System.out.println(LOG + "config " + method.getName() + " = " + value);
        if (value == null && method.getReturnType().isEnum()) {
          System.out.println(
              LOG + "  WARNING: null enum, available constants: "
                  + Arrays.toString(method.getReturnType().getEnumConstants()));
        }
      } catch (Exception e) {
        System.out.println(LOG + "config " + method.getName() + " threw " + e);
      }
    }
  }
}
