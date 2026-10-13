package com.payverse.auth;

import com.payverse.common.test.HexagonalArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.payverse.auth", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.payverse.auth";

    @ArchTest
    static final ArchRule domainIsFrameworkFree = HexagonalArchitectureRules.domainIsFrameworkFree(BASE);

    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = HexagonalArchitectureRules.domainDoesNotDependOnOuterLayers(BASE);

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = HexagonalArchitectureRules.applicationDoesNotDependOnAdapters(BASE);

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfrastructure = HexagonalArchitectureRules.apiDoesNotDependOnInfrastructure(BASE);

    @ArchTest
    static final ArchRule controllersLiveInApi = HexagonalArchitectureRules.controllersLiveInApi(BASE);

    @ArchTest
    static final ArchRule noFieldInjection = HexagonalArchitectureRules.noFieldInjection();
}
