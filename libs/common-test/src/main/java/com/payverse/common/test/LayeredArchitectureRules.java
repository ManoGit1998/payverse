package com.payverse.common.test;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Package-by-layer rules every service must pass. Service layout:
 * <pre>
 * com.payverse.&lt;service&gt;
 * ├── config      Spring configuration and @ConfigurationProperties
 * ├── controller  REST controllers (public and /internal)
 * ├── dto         request / response records
 * ├── entity      JPA entities (+ their enums) holding state and state rules
 * ├── repository  Spring Data repositories and Redis stores
 * ├── service     business logic and transactions
 * ├── security    token / key / hashing components
 * └── (optional)  exception, model, mapper, messaging, client
 * </pre>
 * Usage:
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.payverse.auth", importOptions = ImportOption.DoNotIncludeTests.class)
 * class ArchitectureTest {
 *     @ArchTest static final ArchRule controllers = LayeredArchitectureRules.controllersDoNotAccessRepositories("com.payverse.auth");
 * }
 * }</pre>
 */
public final class LayeredArchitectureRules {

    private LayeredArchitectureRules() {
    }

    public static ArchRule controllersLiveInControllerPackage(String basePackage) {
        return classes().that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAPackage(basePackage + ".controller..")
                .allowEmptyShould(true);
    }

    public static ArchRule controllersDoNotAccessRepositories(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".controller..")
                .should().dependOnClassesThat().resideInAPackage(basePackage + ".repository..")
                .because("controllers go through services")
                .allowEmptyShould(true);
    }

    public static ArchRule servicesDoNotDependOnControllers(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".service..")
                .should().dependOnClassesThat().resideInAPackage(basePackage + ".controller..")
                .allowEmptyShould(true);
    }

    public static ArchRule repositoriesDoNotDependOnUpperLayers(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".repository..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        basePackage + ".service..", basePackage + ".controller..", basePackage + ".dto..")
                .allowEmptyShould(true);
    }

    /** Entities hold state and invariants only: no services, repositories, controllers, DTOs or Spring beans. */
    public static ArchRule entitiesAreSelfContained(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".entity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        basePackage + ".service..", basePackage + ".controller..", basePackage + ".dto..",
                        basePackage + ".repository..", "org.springframework.stereotype..",
                        "org.springframework.beans.factory..")
                .allowEmptyShould(true);
    }

    public static ArchRule dtosDoNotDependOnServicesOrRepositories(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".dto..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        basePackage + ".service..", basePackage + ".repository..", basePackage + ".controller..")
                .allowEmptyShould(true);
    }

    public static ArchRule noFieldInjection() {
        return noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("use constructor injection")
                .allowEmptyShould(true);
    }
}
