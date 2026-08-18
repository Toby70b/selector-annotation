package com.example.selector;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Generates a recursive, null-safe selector for every type annotated with
 * {@link GenerateSelector} and, transitively, for every model type reachable from it.
 *
 * <h2>What gets generated</h2>
 * For a model type {@code Payment} the processor writes {@code PaymentSelector} into
 * {@code Payment}'s own package:
 *
 * <pre>{@code
 * public final class PaymentSelector {
 *     public static PaymentSelector of(Payment value)  // entry point
 *     public Payment orNull()                          // terminal: unwrap, may be null
 *     public Payment orElse(Payment fallback)          // terminal: unwrap with default
 *     public Optional<Payment> asOptional()            // terminal: unwrap as Optional
 *
 *     public String id()                 // leaf property  -> raw nullable value
 *     public Integer retryCount()        // primitive leaf -> boxed, so it can be null
 *     public PartySelector creditor()    // model property -> nested selector, never null
 * }
 * }</pre>
 *
 * The wrapped value is allowed to be null at every level. A model-typed accessor always
 * returns a non-null selector wrapping a possibly-null value, which is what makes an
 * arbitrarily long chain safe to call without intermediate null checks.
 *
 * <p>Every type in the emitted source is written fully qualified. That is deliberate: it costs
 * some readability but removes any possibility of the generated file failing to compile because
 * of an unimported or ambiguous simple name.
 *
 * <h2>Which properties become methods</h2>
 * <ul>
 *   <li><b>JavaBeans getters</b> — public, non-static, no parameters, non-void, named
 *       {@code getXxx()}, or {@code isXxx()} when the return type is {@code boolean}/
 *       {@code Boolean}. {@code getClass()} is excluded.</li>
 *   <li><b>Record components</b> — for records the accessors are named after the component
 *       ({@code bic()} rather than {@code getBic()}), so they are picked up by component
 *       rather than by naming convention.</li>
 *   <li>Inherited getters are included. Where a subtype overrides a getter with a narrower
 *       return type, the most specific one wins.</li>
 * </ul>
 * Property names follow the JavaBeans rule: {@code getBic() -> bic()}, but an acronym-style
 * name keeps its case, so {@code getURL() -> URL()}. A property whose name would be a Java
 * keyword ({@code getDefault() -> default}) keeps its accessor name instead, since
 * {@code default()} is not a legal method name.
 *
 * <h2>Which property types are descended into</h2>
 * A property type is a <em>model type</em> — meaning a nested selector is generated for it and
 * returned — when it is a declared class, record or interface, is not an enum or annotation, is
 * not a {@code java.* / javax.* / jakarta.*} type, and sits under {@link #OPTION_BASE_PACKAGE}.
 * Everything else is a <em>leaf</em> and is returned as its own nullable value. In particular
 * collections, maps, {@code Optional}, dates, enums and arrays are leaves — this processor
 * navigates object graphs, it does not iterate them.
 *
 * <h2>Options</h2>
 * {@code -Aselector.basePackage=com.acme.model} restricts which property types count as model
 * types. When unset, <em>any</em> declared non-enum type outside the JDK is descended into,
 * including third-party types — always set it in a real project.
 *
 * <h2>Limitations</h2>
 * Generic property types are emitted erased ({@code List<T>} becomes {@code List}), because the
 * generated selector declares no type parameters of its own to bind them to.
 */
@SupportedAnnotationTypes("com.example.selector.GenerateSelector")
@SupportedOptions(SelectorProcessor.OPTION_BASE_PACKAGE)
public class SelectorProcessor extends AbstractProcessor {

    /** Compiler option ({@code -A<name>=<value>}) bounding which types are descended into. */
    static final String OPTION_BASE_PACKAGE = "selector.basePackage";

    /** Appended to the model type's simple name to form the selector's simple name. */
    private static final String SELECTOR_SUFFIX = "Selector";

    /** Separates the parts of a nested type's flattened name, e.g. {@code Outer_Inner}. */
    private static final String NESTED_NAME_SEPARATOR = "_";

    /**
     * Terminal methods every generated selector declares. A property that would collide with
     * one of these is skipped rather than emitted, so the selector always compiles.
     */
    private static final Set<String> RESERVED_METHOD_NAMES =
            Set.of("of", "orNull", "orElse", "asOptional");

    /**
     * Primitive property types must be boxed: the accessor returns null when anything earlier
     * in the chain was null, and a primitive cannot express that.
     */
    private static final Map<TypeKind, String> PRIMITIVE_TO_BOXED_TYPE = Map.of(
            TypeKind.INT, "java.lang.Integer",
            TypeKind.LONG, "java.lang.Long",
            TypeKind.DOUBLE, "java.lang.Double",
            TypeKind.BOOLEAN, "java.lang.Boolean",
            TypeKind.FLOAT, "java.lang.Float",
            TypeKind.SHORT, "java.lang.Short",
            TypeKind.BYTE, "java.lang.Byte",
            TypeKind.CHAR, "java.lang.Character");

    private Elements elementUtils;
    private Types typeUtils;
    private Filer filer;
    private Messager messager;

    /** Value of {@link #OPTION_BASE_PACKAGE}; empty means "no restriction". */
    private String modelBasePackage = "";

    /**
     * Fully-qualified names of model types already written, which both prevents a
     * {@code FilerException} from writing the same file twice and terminates recursion
     * around reference cycles such as {@code Payment -> Party -> Payment}.
     */
    private final Set<String> alreadyGeneratedTypes = new HashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
        this.modelBasePackage = processingEnv.getOptions().getOrDefault(OPTION_BASE_PACKAGE, "");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Seeds a work queue with the annotated root types, then drains it. Generating a selector
     * can discover further model types (its model-typed properties), which are appended to the
     * same queue — this is what makes generation recursive without recursing on the call stack.
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        Deque<TypeElement> pendingModelTypes = new ArrayDeque<>();
        for (Element annotated : roundEnv.getElementsAnnotatedWith(GenerateSelector.class)) {
            if (annotated instanceof TypeElement modelType && isSelectableKind(annotated.getKind())) {
                pendingModelTypes.add(modelType);
            } else {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@GenerateSelector applies to a class, record or interface", annotated);
            }
        }

        while (!pendingModelTypes.isEmpty()) {
            TypeElement modelType = pendingModelTypes.poll();
            if (!alreadyGeneratedTypes.add(modelType.getQualifiedName().toString())) {
                continue; // already written, or we have come back round a cycle
            }
            try {
                writeSelectorFor(modelType, pendingModelTypes);
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to generate selector for " + modelType.getQualifiedName()
                                + ": " + e.getMessage(), modelType);
            }
        }
        return true;
    }

    /**
     * Emits the selector source for one model type.
     *
     * <p>Given a model such as:
     *
     * <pre>{@code
     * package com.acme.model;
     *
     * @GenerateSelector
     * public class Payment {
     *     public String getId()          { ... }
     *     public int getRetryCount()     { ... }
     *     public Party getCreditor()     { ... }
     * }
     * }</pre>
     *
     * this method writes {@code com/acme/model/PaymentSelector.java} containing (doc comments
     * on the generated members omitted here for brevity):
     *
     * <pre>{@code
     * package com.acme.model;
     *
     * public final class PaymentSelector {
     *
     *     private final com.acme.model.Payment value;
     *
     *     private PaymentSelector(com.acme.model.Payment value) { this.value = value; }
     *
     *     public static PaymentSelector of(com.acme.model.Payment value) {
     *         return new PaymentSelector(value);
     *     }
     *
     *     public com.acme.model.Payment orNull() { return value; }
     *
     *     public com.acme.model.Payment orElse(com.acme.model.Payment fallback) {
     *         return value != null ? value : fallback;
     *     }
     *
     *     public java.util.Optional<com.acme.model.Payment> asOptional() {
     *         return java.util.Optional.ofNullable(value);
     *     }
     *
     *     // leaf property: the raw value, or null if anything upstream was null
     *     public java.lang.String id() { return value == null ? null : value.getId(); }
     *
     *     // primitive leaf: boxed, so "absent" is distinguishable from 0
     *     public java.lang.Integer retryCount() { return value == null ? null : value.getRetryCount(); }
     *
     *     // model property: another selector, never null, so the chain can continue
     *     public com.acme.model.PartySelector creditor() {
     *         return com.acme.model.PartySelector.of(value == null ? null : value.getCreditor());
     *     }
     * }
     * }</pre>
     *
     * The body of every method is a single expression guarded by {@code value == null}; that
     * guard, repeated at each level, is the whole null-safety mechanism.
     *
     * @param modelType         the type to generate a selector for
     * @param pendingModelTypes queue that model-typed properties are appended to, so they get
     *                          selectors of their own
     */
    private void writeSelectorFor(TypeElement modelType, Deque<TypeElement> pendingModelTypes)
            throws IOException {

        String packageName = elementUtils.getPackageOf(modelType).getQualifiedName().toString();
        String selectorSimpleName = selectorSimpleNameFor(modelType);
        String selectorQualifiedName = qualify(packageName, selectorSimpleName);
        // The model is referenced by qualified name so that nested types (Outer.Inner) and any
        // type-use annotations on the declaration cannot break the emitted source.
        String modelTypeName = modelType.getQualifiedName().toString();

        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("/** Generated null-safe selector for ").append(modelTypeName)
              .append(". Do not edit — regenerated on every build. */\n")
              .append("public final class ").append(selectorSimpleName).append(" {\n\n")
              .append("    /** The wrapped value; null is expected and is what makes chaining safe. */\n")
              .append("    private final ").append(modelTypeName).append(" value;\n\n")
              .append("    private ").append(selectorSimpleName).append('(').append(modelTypeName)
              .append(" value) { this.value = value; }\n\n")
              .append("    /** Starts a chain; the argument may be null. */\n")
              .append("    public static ").append(selectorSimpleName).append(" of(").append(modelTypeName)
              .append(" value) { return new ").append(selectorSimpleName).append("(value); }\n\n")
              .append("    /** Unwraps the value, which may be null. */\n")
              .append("    public ").append(modelTypeName)
              .append(" orNull() { return value; }\n\n")
              .append("    /** Unwraps the value, substituting {@code fallback} when it is null. */\n")
              .append("    public ").append(modelTypeName).append(" orElse(").append(modelTypeName)
              .append(" fallback) { return value != null ? value : fallback; }\n\n")
              .append("    /** Unwraps the value as an Optional. */\n")
              .append("    public java.util.Optional<").append(modelTypeName)
              .append("> asOptional() { return java.util.Optional.ofNullable(value); }\n");

        findPropertiesOf(modelType).forEach((propertyName, accessor) ->
                appendPropertyAccessor(source, accessor, propertyName, pendingModelTypes));
        source.append("}\n");

        try (Writer writer = filer.createSourceFile(selectorQualifiedName, modelType).openWriter()) {
            writer.write(source.toString());
        }
    }

    /**
     * Appends one property method, in one of exactly two shapes.
     *
     * <p>A <b>model</b> property returns the nested selector, so the chain can continue. The
     * null check happens before the nested selector is built, and {@code of} accepts null, so
     * the returned selector is never itself null:
     *
     * <pre>{@code
     * public com.acme.model.PartySelector creditor() {
     *     return com.acme.model.PartySelector.of(value == null ? null : value.getCreditor());
     * }
     * }</pre>
     *
     * A <b>leaf</b> property returns the value itself, boxed if the model declared it primitive:
     *
     * <pre>{@code
     * public java.lang.String id()          { return value == null ? null : value.getId(); }
     * public java.lang.Integer retryCount() { return value == null ? null : value.getRetryCount(); }
     * }</pre>
     *
     * @param pendingModelTypes queue a model-typed property is appended to, so that its own
     *                          selector gets generated in turn
     */
    private void appendPropertyAccessor(StringBuilder source,
                                        ExecutableElement accessor,
                                        String propertyName,
                                        Deque<TypeElement> pendingModelTypes) {

        TypeMirror propertyType = accessor.getReturnType();
        String accessorCall = accessor.getSimpleName() + "()";
        source.append('\n');

        if (isModelType(propertyType)) {
            TypeElement nestedModelType = (TypeElement) typeUtils.asElement(propertyType);
            pendingModelTypes.add(nestedModelType);

            String nestedSelectorName = qualify(
                    elementUtils.getPackageOf(nestedModelType).getQualifiedName().toString(),
                    selectorSimpleNameFor(nestedModelType));

            source.append("    public ").append(nestedSelectorName).append(' ').append(propertyName)
                  .append("() { return ").append(nestedSelectorName)
                  .append(".of(value == null ? null : value.").append(accessorCall).append("); }\n");
        } else {
            source.append("    public ").append(returnTypeNameFor(propertyType)).append(' ')
                  .append(propertyName)
                  .append("() { return value == null ? null : value.").append(accessorCall)
                  .append("; }\n");
        }
    }

    /**
     * Maps property name to the accessor that should implement it, in declaration order.
     *
     * <p>Collects every JavaBeans getter reachable on the type (including inherited ones) plus
     * record component accessors, drops anything clashing with a terminal method, and resolves
     * duplicates in favour of the most specific return type, so a covariant override wins over
     * the declaration it narrows.
     */
    private Map<String, ExecutableElement> findPropertiesOf(TypeElement modelType) {
        Set<Name> recordAccessorNames = recordAccessorNamesOf(modelType);
        Map<String, ExecutableElement> accessorsByPropertyName = new LinkedHashMap<>();

        for (ExecutableElement method : ElementFilter.methodsIn(elementUtils.getAllMembers(modelType))) {
            boolean isProperty = isJavaBeansGetter(method)
                    || recordAccessorNames.contains(method.getSimpleName());
            if (!isProperty) {
                continue;
            }
            String propertyName = propertyNameOf(method, recordAccessorNames);
            if (RESERVED_METHOD_NAMES.contains(propertyName)) {
                continue; // would clash with of/orNull/orElse/asOptional
            }
            ExecutableElement existing = accessorsByPropertyName.get(propertyName);
            if (existing == null || narrowsReturnTypeOf(method, existing)) {
                accessorsByPropertyName.put(propertyName, method);
            }
        }
        return accessorsByPropertyName;
    }

    /** True when {@code candidate} returns a strict subtype of what {@code existing} returns. */
    private boolean narrowsReturnTypeOf(ExecutableElement candidate, ExecutableElement existing) {
        TypeMirror candidateReturn = candidate.getReturnType();
        TypeMirror existingReturn = existing.getReturnType();
        if (candidateReturn.getKind() != TypeKind.DECLARED
                || existingReturn.getKind() != TypeKind.DECLARED) {
            return false;
        }
        return !typeUtils.isSameType(candidateReturn, existingReturn)
                && typeUtils.isSubtype(candidateReturn, existingReturn);
    }

    /** Simple names of the record's component accessors, or empty for a non-record. */
    private Set<Name> recordAccessorNamesOf(TypeElement modelType) {
        if (modelType.getKind() != ElementKind.RECORD) {
            return Set.of();
        }
        Set<Name> names = new LinkedHashSet<>();
        for (RecordComponentElement component
                : ElementFilter.recordComponentsIn(modelType.getEnclosedElements())) {
            names.add(component.getAccessor().getSimpleName());
        }
        return names;
    }

    /**
     * Flattens a possibly-nested type name, so {@code Outer.Inner} yields
     * {@code Outer_InnerSelector} rather than a bare {@code InnerSelector} that could collide
     * with another {@code Inner} in the same package.
     */
    private String selectorSimpleNameFor(TypeElement modelType) {
        Deque<String> nameParts = new ArrayDeque<>();
        for (Element current = modelType;
             current instanceof TypeElement enclosingType;
             current = current.getEnclosingElement()) {
            nameParts.addFirst(enclosingType.getSimpleName().toString());
        }
        return String.join(NESTED_NAME_SEPARATOR, nameParts) + SELECTOR_SUFFIX;
    }

    private static String qualify(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * True when the property type warrants its own selector, i.e. it is one of <em>our</em>
     * model types rather than a scalar, a JDK type or a third-party type.
     */
    private boolean isModelType(TypeMirror propertyType) {
        if (propertyType.getKind() != TypeKind.DECLARED) {
            return false; // primitives, arrays and type variables are always leaves
        }
        Element propertyElement = typeUtils.asElement(propertyType);
        if (!(propertyElement instanceof TypeElement declaredType)
                || !isSelectableKind(propertyElement.getKind())) {
            return false; // enums and annotation types are leaves
        }
        String qualifiedName = declaredType.getQualifiedName().toString();
        if (qualifiedName.startsWith("java.")
                || qualifiedName.startsWith("javax.")
                || qualifiedName.startsWith("jakarta.")) {
            // JDK / Jakarta types — List, Map, Optional, LocalDate, ... — are leaves.
            return false;
        }
        return isUnderBasePackage(qualifiedName);
    }

    /**
     * Prefix match on whole package segments, so a base package of {@code com.acme.model} does
     * not accidentally capture {@code com.acme.modelling}.
     */
    private boolean isUnderBasePackage(String qualifiedName) {
        return modelBasePackage.isEmpty()
                || qualifiedName.equals(modelBasePackage)
                || qualifiedName.startsWith(modelBasePackage + ".");
    }

    /**
     * Source-level type name for a leaf property. Primitives are boxed here — and only here, at
     * the top level — so the accessor can return null; a {@code double[]} stays a
     * {@code double[]} rather than becoming a {@code Double[]}.
     */
    private String returnTypeNameFor(TypeMirror propertyType) {
        String boxed = PRIMITIVE_TO_BOXED_TYPE.get(propertyType.getKind());
        return boxed != null ? boxed : typeNameOf(propertyType);
    }

    /**
     * Renders a type as source, built from element names rather than {@link TypeMirror#toString()}
     * so that type-use annotations on the model ({@code @NotNull String}) cannot leak into the
     * generated file. Type arguments are dropped when any of them mentions a type variable,
     * because the selector declares no type parameters to bind it to.
     */
    private String typeNameOf(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE:
                return type.getKind().name().toLowerCase(Locale.ROOT);
            case ARRAY:
                return typeNameOf(((ArrayType) type).getComponentType()) + "[]";
            case TYPEVAR:
                return typeNameOf(typeUtils.erasure(type));
            case WILDCARD:
                WildcardType wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null) {
                    return "? extends " + typeNameOf(wildcard.getExtendsBound());
                }
                if (wildcard.getSuperBound() != null) {
                    return "? super " + typeNameOf(wildcard.getSuperBound());
                }
                return "?";
            case DECLARED:
                DeclaredType declaredType = (DeclaredType) type;
                String rawName = ((TypeElement) declaredType.asElement()).getQualifiedName().toString();
                List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
                if (typeArguments.isEmpty() || mentionsTypeVariable(declaredType)) {
                    return rawName;
                }
                StringJoiner arguments = new StringJoiner(", ", "<", ">");
                typeArguments.forEach(argument -> arguments.add(typeNameOf(argument)));
                return rawName + arguments;
            default:
                return type.toString();
        }
    }

    /** Whether the type is, or is parameterised by, a type variable such as {@code T}. */
    private boolean mentionsTypeVariable(TypeMirror type) {
        switch (type.getKind()) {
            case TYPEVAR:
                return true;
            case ARRAY:
                return mentionsTypeVariable(((ArrayType) type).getComponentType());
            case WILDCARD:
                WildcardType wildcard = (WildcardType) type;
                return (wildcard.getExtendsBound() != null && mentionsTypeVariable(wildcard.getExtendsBound()))
                        || (wildcard.getSuperBound() != null && mentionsTypeVariable(wildcard.getSuperBound()));
            case DECLARED:
                for (TypeMirror typeArgument : ((DeclaredType) type).getTypeArguments()) {
                    if (mentionsTypeVariable(typeArgument)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    /** Public, non-static, parameterless, non-void {@code getXxx()} / boolean {@code isXxx()}. */
    private boolean isJavaBeansGetter(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        if (!modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.STATIC)) {
            return false;
        }
        if (!method.getParameters().isEmpty() || method.getReturnType().getKind() == TypeKind.VOID) {
            return false;
        }
        String methodName = method.getSimpleName().toString();
        if (methodName.equals("getClass")) {
            return false; // inherited from Object, not a property
        }
        boolean isGetStyle = methodName.startsWith("get") && methodName.length() > 3;
        boolean isBooleanIsStyle = methodName.startsWith("is") && methodName.length() > 2
                && (method.getReturnType().getKind() == TypeKind.BOOLEAN
                    || "java.lang.Boolean".equals(method.getReturnType().toString()));
        return isGetStyle || isBooleanIsStyle;
    }

    /**
     * Derives the selector method name. Record accessors already are the property name; getters
     * have their prefix stripped and are decapitalised per JavaBeans, which leaves an initial
     * acronym alone ({@code getURL() -> URL}, {@code getName() -> name}).
     *
     * <p>If stripping the prefix would produce a Java keyword — {@code getDefault()} would give
     * {@code default()}, which does not compile — the accessor name is kept as-is instead.
     */
    private String propertyNameOf(ExecutableElement accessor, Set<Name> recordAccessorNames) {
        String accessorName = accessor.getSimpleName().toString();
        if (recordAccessorNames.contains(accessor.getSimpleName())) {
            return accessorName; // already the property name; nothing to strip
        }
        String withoutPrefix = accessorName.startsWith("get")
                ? accessorName.substring(3)
                : accessorName.substring(2);

        String propertyName;
        if (withoutPrefix.length() > 1
                && Character.isUpperCase(withoutPrefix.charAt(0))
                && Character.isUpperCase(withoutPrefix.charAt(1))) {
            propertyName = withoutPrefix;
        } else {
            propertyName = Character.toLowerCase(withoutPrefix.charAt(0)) + withoutPrefix.substring(1);
        }
        return SourceVersion.isName(propertyName) ? propertyName : accessorName;
    }

    /** Selectors only make sense for types with properties — not enums or annotations. */
    private static boolean isSelectableKind(ElementKind kind) {
        return kind == ElementKind.CLASS || kind == ElementKind.RECORD || kind == ElementKind.INTERFACE;
    }
}
