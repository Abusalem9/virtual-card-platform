package com.nium.virtualcard;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Executable version of the layering and module rules described in the README.
 */
@AnalyzeClasses(packages = "com.nium.virtualcard", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule entityDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage("..entity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..", "..service..", "..listener..", "..scheduler..", "..repository..", "..dto..",
                    "..event..", "..exception..");

    @ArchTest
    static final ArchRule businessLogicDoesNotDependOnController = noClasses()
            .that().resideInAnyPackage("..service..", "..listener..", "..scheduler..")
            .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule repositoryDoesNotDependOnControllerOrService = noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..", "..service..", "..listener..", "..scheduler..");

    @ArchTest
    static final ArchRule cardModuleKnowsNoOtherFeatureModule = noClasses()
            .that().resideInAPackage("com.nium.virtualcard.card..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.nium.virtualcard.transaction..", "com.nium.virtualcard.audit..");

    @ArchTest
    static final ArchRule transactionModuleDoesNotKnowAudit = noClasses()
            .that().resideInAPackage("com.nium.virtualcard.transaction..")
            .should().dependOnClassesThat().resideInAPackage("com.nium.virtualcard.audit..");

    @ArchTest
    static final ArchRule featureModulesAreFreeOfCycles = slices()
            .matching("com.nium.virtualcard.(card|transaction|audit)..")
            .should().beFreeOfCycles();
}
