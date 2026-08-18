package com.example.selector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cases that sit outside the happy path: alternative declaration forms, generics, misuse of the
 * annotation, and the boundaries of the {@code selector.basePackage} option.
 */
class SelectorEdgeCaseTest {

    @TempDir
    Path workDir;

    private SelectorCompilation.Result compile(String basePackage, Map<String, String> sources) {
        return SelectorCompilation.compile(workDir, basePackage, sources);
    }

    private static Map<String, String> sources(String... typeNameThenSource) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < typeNameThenSource.length; i += 2) {
            sources.put(typeNameThenSource[i], typeNameThenSource[i + 1]);
        }
        return sources;
    }

    @Nested
    @DisplayName("records")
    class Records {

        @Test
        @DisplayName("component accessors become properties, despite having no get prefix")
        void generatesSelectorForRecordComponents() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Money", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;
                            import java.math.BigDecimal;

                            @GenerateSelector
                            public record Money(String currency, BigDecimal amount, int scale) { }
                            """)).assertSucceeded();

            String source = result.generatedSource("com.acme.model.MoneySelector");
            assertTrue(source.contains("public java.lang.String currency()"), source);
            assertTrue(source.contains("public java.math.BigDecimal amount()"), source);
            assertTrue(source.contains("public java.lang.Integer scale()"),
                    "primitive component boxed:\n" + source);
        }

        @Test
        @DisplayName("recursion works through nested records")
        void recursesThroughNestedRecords() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Order", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public record Order(String reference, Customer customer) { }
                            """,
                    "com.acme.model.Customer", """
                            package com.acme.model;

                            public record Customer(String name) { }
                            """)).assertSucceeded();

            assertTrue(result.hasGenerated("com.acme.model.CustomerSelector"));
            assertTrue(result.generatedSource("com.acme.model.OrderSelector")
                    .contains("public com.acme.model.CustomerSelector customer()"));
        }
    }

    @Nested
    @DisplayName("interfaces")
    class Interfaces {

        @Test
        @DisplayName("getters declared on an interface become properties")
        void generatesSelectorForInterface() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.HasReference", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public interface HasReference {
                                String getReference();
                                boolean isSettled();
                            }
                            """)).assertSucceeded();

            String source = result.generatedSource("com.acme.model.HasReferenceSelector");
            assertTrue(source.contains("public java.lang.String reference()"), source);
            assertTrue(source.contains("public java.lang.Boolean settled()"), source);
        }
    }

    @Nested
    @DisplayName("generics")
    class Generics {

        @Test
        @DisplayName("type variables are erased, so the generated selector still compiles")
        void erasesTypeVariables() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Holder", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;
                            import java.util.List;
                            import java.util.Map;

                            @GenerateSelector
                            public class Holder<T> {
                                public T getItem() { return null; }
                                public List<T> getItems() { return null; }
                                public Map<String, T> getIndex() { return null; }
                                public T[] getArray() { return null; }
                                public String getName() { return null; }
                            }
                            """));

            // The point of the test: a type variable in a property type must not leak into the
            // selector, which declares no type parameters of its own to bind it to.
            result.assertSucceeded();

            String source = result.generatedSource("com.acme.model.HolderSelector");
            assertTrue(source.contains("public java.lang.Object item()"), source);
            assertTrue(source.contains("public java.util.List items()"), source);
            assertTrue(source.contains("public java.util.Map index()"), source);
            assertTrue(source.contains("public java.lang.String name()"), source);
        }
    }

    @Nested
    @DisplayName("misuse of the annotation")
    class Misuse {

        @Test
        @DisplayName("an enum is rejected with a clear error")
        void rejectsEnum() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Status", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public enum Status { PENDING, SETTLED }
                            """));

            assertFalse(result.succeeded(), "annotating an enum should fail the build");
            assertTrue(result.errors().stream()
                            .anyMatch(message -> message.contains("class, record or interface")),
                    "expected an explanatory error, got: " + result.errors());
        }

        @Test
        @DisplayName("an annotation type is rejected")
        void rejectsAnnotationType() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Marker", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public @interface Marker { }
                            """));

            assertFalse(result.succeeded(), "annotating an annotation type should fail the build");
        }
    }

    @Nested
    @DisplayName("the selector.basePackage option")
    class BasePackageOption {

        @Test
        @DisplayName("matches whole package segments, not bare prefixes")
        void doesNotMatchPartialPackageSegment() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Root", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;
                            import com.acme.modelling.Sneaky;

                            @GenerateSelector
                            public class Root {
                                public Sneaky getSneaky() { return null; }
                            }
                            """,
                    "com.acme.modelling.Sneaky", """
                            package com.acme.modelling;

                            public class Sneaky {
                                public String getName() { return null; }
                            }
                            """)).assertSucceeded();

            // "com.acme.modelling" starts with "com.acme.model" as a string, but is a different
            // package, so it must be treated as a leaf.
            assertFalse(result.hasGenerated("com.acme.modelling.SneakySelector"),
                    "generated: " + result.generatedTypeNames());
            assertTrue(result.generatedSource("com.acme.model.RootSelector")
                    .contains("public com.acme.modelling.Sneaky sneaky()"));
        }

        @Test
        @DisplayName("unset by default: recursion follows every referenced type outside the JDK")
        void unboundedByDefault() {
            SelectorCompilation.Result result =
                    compile(null, TestModels.paymentGraph()).assertSucceeded();

            assertTrue(result.hasGenerated("com.acme.model.PartySelector"));
            assertTrue(result.hasGenerated("com.acme.model.FinancialInstitutionSelector"));
            assertTrue(result.hasGenerated("com.thirdparty.VendorSelector"),
                    "with no bound set, a referenced third-party type is descended into too; "
                            + "generated: " + result.generatedTypeNames());
        }

        @Test
        @DisplayName("setting a bound keeps generation to your own code")
        void boundExcludesThirdPartyTypes() {
            SelectorCompilation.Result result =
                    compile("com.acme", TestModels.paymentGraph()).assertSucceeded();

            assertTrue(result.hasGenerated("com.acme.model.PartySelector"));
            assertFalse(result.hasGenerated("com.thirdparty.VendorSelector"),
                    "generated: " + result.generatedTypeNames());
        }
    }

    @Nested
    @DisplayName("choosing the base package")
    class BasePackageResolution {

        /** A root in one package with a property type in a sibling package. */
        private Map<String, String> siblingPackages(String annotation) {
            return sources(
                    "com.acme.payments.Payment", """
                            package com.acme.payments;

                            import com.example.selector.GenerateSelector;
                            import com.acme.parties.Party;

                            %s
                            public class Payment {
                                public Party getPayee() { return null; }
                            }
                            """.formatted(annotation),
                    "com.acme.parties.Party", """
                            package com.acme.parties;

                            public class Party {
                                public String getName() { return null; }
                            }
                            """);
        }

        @Test
        @DisplayName("by default a referenced type in a sibling package is followed")
        void followsReferencesAcrossPackagesByDefault() {
            SelectorCompilation.Result result =
                    compile(null, siblingPackages("@GenerateSelector")).assertSucceeded();

            // Payment is in com.acme.payments and Party in com.acme.parties: package layout
            // does not matter, only that Payment references Party.
            assertTrue(result.hasGenerated("com.acme.parties.PartySelector"),
                    "generated: " + result.generatedTypeNames());
        }

        @Test
        @DisplayName("the annotation's value narrows it, with no compiler args")
        void annotationValueNarrowsTheBound() {
            SelectorCompilation.Result result =
                    compile(null, siblingPackages("@GenerateSelector(\"com.acme.payments\")"))
                            .assertSucceeded();

            assertFalse(result.hasGenerated("com.acme.parties.PartySelector"),
                    "com.acme.parties is outside the declared bound; generated: "
                            + result.generatedTypeNames());
        }

        @Test
        @DisplayName("the compiler option still works as a project-wide bound")
        void compilerOptionStillApplies() {
            SelectorCompilation.Result result =
                    compile("com.acme.payments", siblingPackages("@GenerateSelector"))
                            .assertSucceeded();

            assertFalse(result.hasGenerated("com.acme.parties.PartySelector"),
                    "generated: " + result.generatedTypeNames());
        }

        @Test
        @DisplayName("the annotation wins over the compiler option")
        void annotationBeatsCompilerOption() {
            // The option alone would exclude Party; the annotation widens it back to com.acme.
            SelectorCompilation.Result result =
                    compile("com.acme.payments", siblingPackages("@GenerateSelector(\"com.acme\")"))
                            .assertSucceeded();

            assertTrue(result.hasGenerated("com.acme.parties.PartySelector"),
                    "generated: " + result.generatedTypeNames());
        }
    }

    @Nested
    @DisplayName("nested model types")
    class NestedTypes {

        @Test
        @DisplayName("an inner class is referenced by qualified name and gets a flattened selector")
        void handlesNestedModelTypes() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Envelope", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public class Envelope {
                                public Header getHeader() { return null; }

                                public static class Header {
                                    public String getMessageId() { return null; }
                                }
                            }
                            """));

            // Before the fix the selector referred to the inner class by its bare simple name
            // ("Header"), which does not resolve at package level, so this would not compile.
            result.assertSucceeded();

            assertTrue(result.hasGenerated("com.acme.model.Envelope_HeaderSelector"),
                    "generated: " + result.generatedTypeNames());
            assertTrue(result.generatedSource("com.acme.model.EnvelopeSelector")
                            .contains("public com.acme.model.Envelope_HeaderSelector header()"),
                    result.generatedSource("com.acme.model.EnvelopeSelector"));
            assertTrue(result.generatedSource("com.acme.model.Envelope_HeaderSelector")
                    .contains("com.acme.model.Envelope.Header value"));
        }
    }

    @Nested
    @DisplayName("property names that are Java keywords")
    class KeywordProperties {

        @Test
        @DisplayName("fall back to the accessor name rather than emitting an illegal method")
        void doesNotEmitKeywordMethodNames() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Config", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public class Config {
                                public String getDefault() { return null; }
                                public String getNew() { return null; }
                                public String getPackage() { return null; }
                                public String getName() { return null; }
                            }
                            """));

            // "default", "new" and "package" are keywords: emitting default() would not compile.
            result.assertSucceeded();

            String source = result.generatedSource("com.acme.model.ConfigSelector");
            assertTrue(source.contains("public java.lang.String getDefault()"), source);
            assertTrue(source.contains("public java.lang.String getNew()"), source);
            assertTrue(source.contains("public java.lang.String getPackage()"), source);
            assertTrue(source.contains("public java.lang.String name()"), source);
        }
    }

    @Nested
    @DisplayName("covariant overrides")
    class CovariantOverrides {

        @Test
        @DisplayName("the most specific return type wins")
        void prefersTheNarrowestReturnType() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Specific", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public class Specific extends General {
                                @Override
                                public String getPayload() { return null; }
                            }
                            """,
                    "com.acme.model.General", """
                            package com.acme.model;

                            public class General {
                                public Object getPayload() { return null; }
                            }
                            """)).assertSucceeded();

            String source = result.generatedSource("com.acme.model.SpecificSelector");
            assertTrue(source.contains("public java.lang.String payload()"),
                    "expected the narrowed String override, not Object:\n" + source);
            assertFalse(source.contains("public java.lang.Object payload()"), source);
        }
    }

    @Nested
    @DisplayName("type-use annotations on the model")
    class TypeUseAnnotations {

        @Test
        @DisplayName("do not leak into the generated source")
        void stripsTypeUseAnnotations() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Annotated", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;
                            import java.util.List;

                            @GenerateSelector
                            public class Annotated {
                                public @NotBlank String getName() { return null; }
                                public List<@NotBlank String> getAliases() { return null; }
                            }
                            """,
                    "com.acme.model.NotBlank", """
                            package com.acme.model;

                            import java.lang.annotation.ElementType;
                            import java.lang.annotation.Retention;
                            import java.lang.annotation.RetentionPolicy;
                            import java.lang.annotation.Target;

                            @Target(ElementType.TYPE_USE)
                            @Retention(RetentionPolicy.RUNTIME)
                            public @interface NotBlank { }
                            """));

            // TypeMirror.toString() includes type-use annotations; emitting them verbatim
            // produced source like "public @NotBlank java.lang.String name()".
            result.assertSucceeded();

            String source = result.generatedSource("com.acme.model.AnnotatedSelector");
            assertFalse(source.contains("@NotBlank"), "annotation leaked into:\n" + source);
            assertTrue(source.contains("public java.lang.String name()"), source);
            assertTrue(source.contains("public java.util.List<java.lang.String> aliases()"), source);
        }
    }

    @Nested
    @DisplayName("name clashes")
    class NameClashes {

        @Test
        @DisplayName("properties clashing with terminal methods are skipped, keeping the selector valid")
        void skipsPropertiesClashingWithTerminals() {
            SelectorCompilation.Result result = compile("com.acme.model", sources(
                    "com.acme.model.Awkward", """
                            package com.acme.model;

                            import com.example.selector.GenerateSelector;

                            @GenerateSelector
                            public class Awkward {
                                public String getOf() { return null; }
                                public String getOrNull() { return null; }
                                public String getOrElse() { return null; }
                                public String getAsOptional() { return null; }
                                public String getKept() { return null; }
                            }
                            """)).assertSucceeded();

            String source = result.generatedSource("com.acme.model.AwkwardSelector");
            assertTrue(source.contains("public java.lang.String kept()"), source);
            assertFalse(source.contains("public java.lang.String of()"), source);
            assertFalse(source.contains("public java.lang.String orNull()"), source);
        }
    }
}
