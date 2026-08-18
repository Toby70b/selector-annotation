package com.example.selector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts on the source the processor emits for the {@link TestModels#paymentGraph()} fixture:
 * which selectors exist, and which methods each one declares.
 */
class SelectorGenerationTest {

    @TempDir
    Path workDir;

    private SelectorCompilation.Result result;

    @BeforeEach
    void compileFixture() {
        result = SelectorCompilation
                .compile(workDir, TestModels.BASE_PACKAGE, TestModels.paymentGraph())
                .assertSucceeded();
    }

    private String paymentSelector() {
        return result.generatedSource("com.acme.model.PaymentSelector");
    }

    @Nested
    @DisplayName("which selectors get generated")
    class GeneratedTypes {

        @ParameterizedTest
        @ValueSource(strings = {
                "com.acme.model.PaymentSelector",
                "com.acme.model.PartySelector",
                "com.acme.model.AgentSelector",
                "com.acme.model.FinancialInstitutionSelector",
                "com.acme.model.AmountSelector"})
        @DisplayName("every model type reachable from the annotated root")
        void generatesSelectorForEachReachableModelType(String selectorTypeName) {
            assertTrue(result.hasGenerated(selectorTypeName),
                    "expected a selector for " + selectorTypeName
                            + " but generated " + result.generatedTypeNames());
        }

        @Test
        @DisplayName("only the annotated root needs the annotation — recursion covers the rest")
        void reachesFourLevelsDeepFromASingleAnnotation() {
            // Payment -> Party -> Agent -> FinancialInstitution, with only Payment annotated.
            assertTrue(result.hasGenerated("com.acme.model.FinancialInstitutionSelector"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "com.acme.model.StatusSelector",      // enum
                "com.thirdparty.VendorSelector",      // outside -Aselector.basePackage
                "com.acme.model.BaseEntitySelector"}) // a supertype, not a property type
        @DisplayName("no selector for enums, out-of-package types or supertypes")
        void doesNotGenerateSelectorsForNonModelTypes(String selectorTypeName) {
            assertFalse(result.hasGenerated(selectorTypeName),
                    "should not have generated " + selectorTypeName);
        }

        @Test
        @DisplayName("a type referenced twice is generated once")
        void generatesSharedNestedTypeOnce() {
            // Payment exposes both getCreditor() and getDebtor(), each a Party.
            long partySelectors = result.generatedTypeNames().stream()
                    .filter("com.acme.model.PartySelector"::equals)
                    .count();
            assertTrue(partySelectors == 1, "expected exactly one PartySelector, got " + partySelectors);
        }
    }

    @Nested
    @DisplayName("leaf properties")
    class LeafProperties {

        @Test
        @DisplayName("reference types are returned as-is")
        void emitsReferenceLeafAsRawType() {
            assertTrue(paymentSelector().contains("public java.lang.String id()"));
        }

        @ParameterizedTest(name = "{0} is boxed to {1}")
        @DisplayName("primitives are boxed so the accessor can return null")
        @CsvSource({
                "retryCount, java.lang.Integer",
                "flag,       java.lang.Character",
                "urgent,     java.lang.Boolean"})
        void boxesPrimitiveLeaves(String property, String boxedType) {
            assertTrue(paymentSelector().contains("public " + boxedType + " " + property + "()"),
                    "expected boxed accessor for " + property + " in:\n" + paymentSelector());
        }

        @Test
        @DisplayName("an already-boxed property keeps its type")
        void leavesWrapperTypesAlone() {
            assertTrue(paymentSelector().contains("public java.lang.Boolean verified()"));
        }

        @ParameterizedTest
        @DisplayName("enums, collections, maps, JDK types, arrays and foreign types stay leaves")
        @ValueSource(strings = {
                "public com.acme.model.Status status()",
                "public java.util.List<java.lang.String> tags()",
                "public java.time.LocalDate valueDate()",
                "public double[] rates()",
                "public com.thirdparty.Vendor vendor()"})
        void emitsLeafTypesVerbatim(String expectedSignature) {
            assertTrue(paymentSelector().contains(expectedSignature),
                    "expected '" + expectedSignature + "' in:\n" + paymentSelector());
        }

        @Test
        @DisplayName("a Map of model values is a leaf — the processor navigates, it does not iterate")
        void doesNotDescendIntoMapValues() {
            assertTrue(paymentSelector().contains("breakdown()"));
            assertTrue(paymentSelector().contains("java.util.Map<"));
        }
    }

    @Nested
    @DisplayName("model properties")
    class ModelProperties {

        @Test
        @DisplayName("return the nested selector, so the chain continues")
        void returnsNestedSelector() {
            assertTrue(paymentSelector().contains("public com.acme.model.PartySelector creditor()"));
            assertTrue(paymentSelector().contains("public com.acme.model.AmountSelector amount()"));
        }

        @Test
        @DisplayName("a self-referencing property returns the same selector type")
        void handlesSelfReference() {
            assertTrue(paymentSelector().contains("public com.acme.model.PaymentSelector parent()"));
        }

        @Test
        @DisplayName("the nested accessor short-circuits on a null wrapper")
        void nestedAccessorIsNullSafe() {
            assertTrue(paymentSelector().contains(
                    "com.acme.model.PartySelector.of(value == null ? null : value.getCreditor())"));
        }
    }

    @Nested
    @DisplayName("property naming")
    class PropertyNaming {

        @Test
        @DisplayName("getBic() becomes bic()")
        void decapitalisesOrdinaryGetters() {
            assertTrue(result.generatedSource("com.acme.model.FinancialInstitutionSelector")
                    .contains("public java.lang.String bic()"));
        }

        @Test
        @DisplayName("getURL() keeps its acronym casing, per JavaBeans")
        void preservesAcronymCasing() {
            assertTrue(paymentSelector().contains("public java.lang.String URL()"),
                    "expected URL() in:\n" + paymentSelector());
            assertFalse(paymentSelector().contains("uRL()"));
        }

        @Test
        @DisplayName("isUrgent() becomes urgent()")
        void stripsIsPrefixForBooleans() {
            assertTrue(paymentSelector().contains(" urgent()"));
        }

        @Test
        @DisplayName("getters inherited from a supertype are included")
        void includesInheritedGetters() {
            assertTrue(paymentSelector().contains("correlationId()"),
                    "expected the inherited correlationId property in:\n" + paymentSelector());
        }
    }

    @Nested
    @DisplayName("methods that are not properties")
    class NonProperties {

        @ParameterizedTest(name = "ignores {0}")
        @ValueSource(strings = {
                "staticThing",   // public static
                "nothing",       // void return
                "withParam",     // takes an argument
                "protectedThing",// not public
                "secret",        // private
                "notAGetter",    // no get/is prefix
                "notBoolean"})   // is-prefixed but returns String
        void excludesNonProperties(String propertyName) {
            assertFalse(paymentSelector().contains(propertyName),
                    "should not have emitted " + propertyName + " in:\n" + paymentSelector());
        }

        @Test
        @DisplayName("getClass() inherited from Object is not a property")
        void excludesGetClass() {
            assertFalse(paymentSelector().contains("public java.lang.Class"));
        }

        @Test
        @DisplayName("a bare get() is not a property")
        void excludesBareGet() {
            assertFalse(paymentSelector().contains("public java.lang.String get()"));
        }
    }

    @Nested
    @DisplayName("terminal methods")
    class TerminalMethods {

        @Test
        void declaresEntryPointAndUnwrappers() {
            String source = paymentSelector();
            assertTrue(source.contains("public static PaymentSelector of(com.acme.model.Payment value)"),
                    "expected a static of(..) factory in:\n" + source);
            assertTrue(source.contains("public com.acme.model.Payment orNull()"), source);
            assertTrue(source.contains(
                    "public com.acme.model.Payment orElse(com.acme.model.Payment fallback)"), source);
            assertTrue(source.contains(
                    "public java.util.Optional<com.acme.model.Payment> asOptional()"), source);
        }
    }
}
