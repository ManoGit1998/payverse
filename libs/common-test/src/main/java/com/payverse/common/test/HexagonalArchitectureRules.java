package com.payverse.common.test;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Architecture rules every service must pass. Usage in a service:
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.payverse.wallet", importOptions = ImportOption.DoNotIncludeTests.class)
 * class ArchitectureTest {
 *     @ArchTest static final ArchRule domainIsPure = HexagonalArchitectureRules.domainIsFrameworkFree("com.payverse.wallet");
 *     ...
 * }
 * }</pre>
 */
public final class HexagonalArchitectureRules {

    private HexagonalArchitectureRules() {
    }

    /** The domain layer may use only the JDK, other domain classes and the pure PayVerse libraries. */
    public static ArchRule domainIsFrameworkFree(String basePackage) {
        return classes().that().resideInAPackage(basePackage + ".domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        basePackage + ".domain..",
                        "com.payverse.common.money..",
                        "com.payverse.common.error")
                .because("the domain must not depend on Spring, JPA, Jackson or infrastructure")
                .allowEmptyShould(true);
    }

    public static ArchRule applicationDoesNotDependOnAdapters(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(basePackage + ".api..", basePackage + ".infrastructure..")
                .because("the application layer talks to adapters only through ports")
                .allowEmptyShould(true);
    }

    public static ArchRule apiDoesNotDependOnInfrastructure(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".api..")
                .should().dependOnClassesThat().resideInAPackage(basePackage + ".infrastructure..")
                .because("controllers must go through application use cases")
                .allowEmptyShould(true);
    }

    public static ArchRule domainDoesNotDependOnOuterLayers(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        basePackage + ".application..", basePackage + ".api..", basePackage + ".infrastructure..")
                .allowEmptyShould(true);
    }

    public static ArchRule controllersLiveInApi(String basePackage) {
        return classes().that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAPackage(basePackage + ".api..")
                .allowEmptyShould(true);
    }

    public static ArchRule noFieldInjection() {
        return noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("use constructor injection")
                .allowEmptyShould(true);
    }
}
