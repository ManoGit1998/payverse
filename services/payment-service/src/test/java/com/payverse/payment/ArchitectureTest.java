package com.payverse.payment;

import com.payverse.common.test.LayeredArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.payverse.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.payverse.payment";

    @ArchTest
    static final ArchRule controllersLiveInControllerPackage = LayeredArchitectureRules.controllersLiveInControllerPackage(BASE);

    @ArchTest
    static final ArchRule controllersDoNotAccessRepositories = LayeredArchitectureRules.controllersDoNotAccessRepositories(BASE);

    @ArchTest
    static final ArchRule servicesDoNotDependOnControllers = LayeredArchitectureRules.servicesDoNotDependOnControllers(BASE);

    @ArchTest
    static final ArchRule repositoriesDoNotDependOnUpperLayers = LayeredArchitectureRules.repositoriesDoNotDependOnUpperLayers(BASE);

    @ArchTest
    static final ArchRule entitiesAreSelfContained = LayeredArchitectureRules.entitiesAreSelfContained(BASE);

    @ArchTest
    static final ArchRule dtosDoNotDependOnServicesOrRepositories = LayeredArchitectureRules.dtosDoNotDependOnServicesOrRepositories(BASE);

    @ArchTest
    static final ArchRule noFieldInjection = LayeredArchitectureRules.noFieldInjection();
}
