package com.example.selector;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles the fixture graph, loads the generated selectors and exercises them for real.
 *
 * <p>{@link SelectorGenerationTest} checks the emitted source says the right thing; this class
 * checks the compiled result <em>behaves</em> the right way — above all that a chain over a
 * missing intermediate yields null instead of throwing.
 */
class SelectorNullSafetyTest {

    private static ClassLoader models;

    @BeforeAll
    static void compileFixtureOnce(@TempDir Path workDir) {
        models = SelectorCompilation
                .compile(workDir, TestModels.BASE_PACKAGE, TestModels.paymentGraph())
                .assertSucceeded()
                .classLoader();
    }

    // --- reflection helpers -------------------------------------------------------------

    private static Class<?> type(String simpleName) throws Exception {
        return models.loadClass("com.acme.model." + simpleName);
    }

    private static Object instanceOf(String simpleName) throws Exception {
        return type(simpleName).getDeclaredConstructor().newInstance();
    }

    /** Calls a uniquely-named setter without needing to spell out its parameter type. */
    private static void set(Object target, String setterName, Object value) throws Exception {
        Method setter = Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().equals(setterName) && m.getParameterCount() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no setter " + setterName));
        setter.invoke(target, value);
    }

    /** Wraps a model value in its generated selector via the static {@code of} factory. */
    private static Object selectorFor(String modelSimpleName, Object value) throws Exception {
        Class<?> selectorType = models.loadClass("com.acme.model." + modelSimpleName + "Selector");
        return selectorType.getMethod("of", type(modelSimpleName)).invoke(null, value);
    }

    /** Walks a chain of no-arg selector methods, mirroring a real call site. */
    private static Object chain(Object selector, String... methodNames) throws Exception {
        Object current = selector;
        for (String methodName : methodNames) {
            Method method = current.getClass().getMethod(methodName);
            current = method.invoke(current);
        }
        return current;
    }

    /** A Payment with the full creditor -> agent -> financialInstitution -> bic path populated. */
    private static Object fullyPopulatedPayment() throws Exception {
        Object institution = instanceOf("FinancialInstitution");
        set(institution, "setBic", "NWBKGB2L");

        Object agent = instanceOf("Agent");
        set(agent, "setFinancialInstitution", institution);

        Object creditor = instanceOf("Party");
        set(creditor, "setName", "ACME Ltd");
        set(creditor, "setAgent", agent);

        Object amount = instanceOf("Amount");
        set(amount, "setValue", new BigDecimal("10.00"));
        set(amount, "setCurrency", "GBP");

        Object payment = instanceOf("Payment");
        set(payment, "setId", "PMT-001");
        set(payment, "setRetryCount", 3);
        set(payment, "setUrgent", true);
        set(payment, "setCreditor", creditor);
        set(payment, "setAmount", amount);
        set(payment, "setCorrelationId", "CORR-9");
        return payment;
    }

    // --- the behaviour that matters -----------------------------------------------------

    @Nested
    @DisplayName("a fully populated graph")
    class Populated {

        @Test
        @DisplayName("returns the deep value")
        void returnsDeepValue() throws Exception {
            Object selector = selectorFor("Payment", fullyPopulatedPayment());
            Object bic = chain(selector, "creditor", "agent", "financialInstitution", "bic");
            assertEquals("NWBKGB2L", bic);
        }

        @Test
        @DisplayName("returns shallow values, boxed where the source was primitive")
        void returnsShallowValues() throws Exception {
            Object selector = selectorFor("Payment", fullyPopulatedPayment());
            assertEquals("PMT-001", chain(selector, "id"));
            assertEquals(3, chain(selector, "retryCount"));
            assertEquals(true, chain(selector, "urgent"));
            assertEquals("GBP", chain(selector, "amount", "currency"));
        }

        @Test
        @DisplayName("returns properties inherited from a supertype")
        void returnsInheritedProperty() throws Exception {
            Object selector = selectorFor("Payment", fullyPopulatedPayment());
            assertEquals("CORR-9", chain(selector, "correlationId"));
        }
    }

    @Nested
    @DisplayName("a missing intermediate")
    class MissingIntermediate {

        @Test
        @DisplayName("yields null rather than throwing")
        void deepChainOverNullIntermediateReturnsNull() throws Exception {
            Object payment = instanceOf("Payment"); // creditor never set
            Object selector = selectorFor("Payment", payment);
            assertNull(chain(selector, "creditor", "agent", "financialInstitution", "bic"));
        }

        @Test
        @DisplayName("still returns a usable selector at every hop")
        void intermediateSelectorsAreNeverNull() throws Exception {
            Object selector = selectorFor("Payment", instanceOf("Payment"));
            assertNotNull(chain(selector, "creditor"));
            assertNotNull(chain(selector, "creditor", "agent"));
            assertNotNull(chain(selector, "creditor", "agent", "financialInstitution"));
        }

        @Test
        @DisplayName("a half-populated path stops at the gap")
        void partiallyPopulatedChainReturnsNull() throws Exception {
            Object creditor = instanceOf("Party"); // agent never set
            Object payment = instanceOf("Payment");
            set(payment, "setCreditor", creditor);

            Object selector = selectorFor("Payment", payment);
            // the populated hop still resolves...
            assertNotNull(chain(selector, "creditor", "orNull"));
            // ...but the chain past the gap yields null instead of throwing
            assertNull(chain(selector, "creditor", "agent", "financialInstitution", "bic"));
        }
    }

    @Nested
    @DisplayName("a null root")
    class NullRoot {

        @Test
        @DisplayName("chains all the way down without throwing")
        void nullRootChainsToNull() throws Exception {
            Object selector = selectorFor("Payment", null);
            assertNull(chain(selector, "creditor", "agent", "financialInstitution", "bic"));
        }

        @Test
        @DisplayName("a boxed primitive property returns null, not a default value")
        void nullRootReturnsNullForPrimitiveProperty() throws Exception {
            Object selector = selectorFor("Payment", null);
            assertNull(chain(selector, "retryCount"), "boxing exists precisely so this can be null");
            assertNull(chain(selector, "urgent"));
            assertNull(chain(selector, "flag"));
        }
    }

    @Nested
    @DisplayName("cycles")
    class Cycles {

        @Test
        @DisplayName("a self-reference can be chained repeatedly")
        void selfReferenceChains() throws Exception {
            Object selector = selectorFor("Payment", instanceOf("Payment"));
            assertNull(chain(selector, "parent", "parent", "parent", "id"));
        }

        @Test
        @DisplayName("a two-type cycle can be chained back and forth")
        void twoTypeCycleChains() throws Exception {
            Object selector = selectorFor("Payment", fullyPopulatedPayment());
            // Payment -> Party -> Payment -> Party ...
            assertNull(chain(selector, "creditor", "payment", "creditor", "name"));
        }
    }

    @Nested
    @DisplayName("terminal methods")
    class Terminals {

        @Test
        void orNullUnwraps() throws Exception {
            Object payment = fullyPopulatedPayment();
            assertSame(payment, chain(selectorFor("Payment", payment), "orNull"));
            assertNull(chain(selectorFor("Payment", null), "orNull"));
        }

        @Test
        void orElseSubstitutesTheFallback() throws Exception {
            Object fallback = instanceOf("Payment");
            Object selector = selectorFor("Payment", null);
            Method orElse = selector.getClass().getMethod("orElse", type("Payment"));
            assertSame(fallback, orElse.invoke(selector, fallback));

            Object present = fullyPopulatedPayment();
            assertSame(present, orElse.invoke(selectorFor("Payment", present), fallback));
        }

        @Test
        void asOptionalReflectsPresence() throws Exception {
            assertTrue(((Optional<?>) chain(selectorFor("Payment", null), "asOptional")).isEmpty());
            assertTrue(((Optional<?>) chain(selectorFor("Payment", fullyPopulatedPayment()), "asOptional"))
                    .isPresent());
        }

        @Test
        @DisplayName("terminals work mid-chain, on a nested selector")
        void terminalsWorkOnNestedSelectors() throws Exception {
            Object selector = selectorFor("Payment", fullyPopulatedPayment());
            assertNotNull(chain(selector, "creditor", "orNull"));
            assertNotNull(chain(selector, "creditor", "agent", "financialInstitution", "orNull"));
            // and on a nested selector wrapping null
            assertNull(chain(selectorFor("Payment", null), "creditor", "orNull"));
        }
    }
}
