package com.payverse.gateway;

import com.payverse.common.test.HexagonalArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The gateway has no domain; it only routes. Keep it free of business logic and blocking I/O. */
@AnalyzeClasses(packages = "com.payverse.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule noFieldInjection = HexagonalArchitectureRules.noFieldInjection();

    @ArchTest
    static final ArchRule controllersLiveInApi = HexagonalArchitectureRules.controllersLiveInApi("com.payverse.gateway");

    @ArchTest
    static final ArchRule noBlockingWebStack = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web.servlet..", "jakarta.servlet..", "org.springframework.jdbc..")
            .because("the gateway is reactive (WebFlux) and owns no database");
}
