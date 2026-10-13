package com.payverse.gateway;

import com.payverse.common.test.LayeredArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The gateway owns no data: packages are config, controller (fallbacks) and filter. Keep it reactive
 * and free of business logic.
 */
@AnalyzeClasses(packages = "com.payverse.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule noFieldInjection = LayeredArchitectureRules.noFieldInjection();

    @ArchTest
    static final ArchRule controllersLiveInControllerPackage = LayeredArchitectureRules.controllersLiveInControllerPackage("com.payverse.gateway");

    @ArchTest
    static final ArchRule noBlockingWebStack = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web.servlet..", "jakarta.servlet..", "org.springframework.jdbc..")
            .because("the gateway is reactive (WebFlux) and owns no database");
}
