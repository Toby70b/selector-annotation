package com.example.selector;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test harness that runs {@link SelectorProcessor} through a real {@code javac} invocation.
 *
 * <p>Fixture models are supplied as in-memory source; generated selectors are written to a
 * temporary directory so tests can assert on the emitted source text, and the compiled output
 * is exposed through a class loader so tests can exercise the selectors at runtime.
 */
final class SelectorCompilation {

    private SelectorCompilation() {
    }

    /**
     * Compiles {@code sourcesByTypeName} with the selector processor attached.
     *
     * @param workDir     temporary directory for generated sources and class files
     * @param basePackage value for {@code -Aselector.basePackage}, or null to leave it unset
     */
    static Result compile(Path workDir, String basePackage, Map<String, String> sourcesByTypeName) {
        Path generatedSourceDir = workDir.resolve("generated-sources");
        Path classesDir = workDir.resolve("classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try {
            Files.createDirectories(generatedSourceDir);
            Files.createDirectories(classesDir);

            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
                fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generatedSourceDir.toFile()));

                // No -proc flag: it is JDK 21+ only, and setProcessors below already runs the
                // processor explicitly, so processing happens on JDK 17 too.
                List<String> options = new ArrayList<>();
                if (basePackage != null) {
                    options.add("-A" + SelectorProcessor.OPTION_BASE_PACKAGE + "=" + basePackage);
                }

                List<JavaFileObject> compilationUnits = sourcesByTypeName.entrySet().stream()
                        .map(entry -> inMemorySource(entry.getKey(), entry.getValue()))
                        .collect(Collectors.toList());

                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics, options, null, compilationUnits);
                // Set the processor explicitly rather than relying on META-INF/services
                // discovery, so these tests exercise the processor itself and not the packaging.
                task.setProcessors(List.of(new SelectorProcessor()));

                boolean succeeded = task.call();
                return new Result(succeeded, diagnostics.getDiagnostics(), generatedSourceDir, classesDir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JavaFileObject inMemorySource(String typeName, String sourceText) {
        URI uri = URI.create("string:///" + typeName.replace('.', '/') + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return sourceText;
            }
        };
    }

    /** Outcome of one compilation, including everything the processor produced. */
    record Result(boolean succeeded,
                  List<Diagnostic<? extends JavaFileObject>> diagnostics,
                  Path generatedSourceDir,
                  Path classesDir) {

        /** Fails the calling test with the compiler output when compilation did not succeed. */
        Result assertSucceeded() {
            if (!succeeded) {
                throw new AssertionError("Compilation failed:\n" + String.join("\n", messages()));
            }
            return this;
        }

        List<String> errors() {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null))
                    .collect(Collectors.toList());
        }

        List<String> warnings() {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                            || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                    .map(d -> d.getMessage(null))
                    .collect(Collectors.toList());
        }

        List<String> messages() {
            return diagnostics.stream()
                    .map(d -> d.getKind() + ": " + d.getMessage(null))
                    .collect(Collectors.toList());
        }

        /** Source text of a generated selector, e.g. {@code "com.acme.model.PaymentSelector"}. */
        String generatedSource(String selectorTypeName) {
            Path file = generatedSourceDir.resolve(selectorTypeName.replace('.', '/') + ".java");
            if (!Files.exists(file)) {
                throw new AssertionError(
                        "No generated source for " + selectorTypeName + "; generated: " + generatedTypeNames());
            }
            try {
                return Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        boolean hasGenerated(String selectorTypeName) {
            return Files.exists(generatedSourceDir.resolve(selectorTypeName.replace('.', '/') + ".java"));
        }

        /** Fully-qualified names of every selector the processor wrote. */
        List<String> generatedTypeNames() {
            try (Stream<Path> files = Files.walk(generatedSourceDir)) {
                return files
                        .filter(path -> path.toString().endsWith(".java"))
                        .map(path -> generatedSourceDir.relativize(path).toString()
                                .replace('\\', '.')
                                .replace('/', '.')
                                .replaceAll("\\.java$", ""))
                        .sorted()
                        .collect(Collectors.toList());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Class loader over the compiled output, for invoking selectors reflectively. */
        ClassLoader classLoader() {
            try {
                URL[] classpath = {classesDir.toUri().toURL()};
                return new URLClassLoader(classpath, SelectorCompilation.class.getClassLoader());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
