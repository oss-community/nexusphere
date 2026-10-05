package com.nexusphere;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Architecture rules of redesign §11, §71 and §80, checked on every build. */
class ArchitectureTest {

    static final List<String> MODULES = List.of(
            "network", "organization", "identity", "membership", "capability", "discovery", "trust", "federation",
            "authorization", "delegation", "agreement", "transaction", "audit", "integration");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nexusphere");

    @Test
    void sharedStaysFrameworkFreeAndSelfContained() {
        classes().that().resideInAPackage("com.nexusphere.shared..")
                .and().doNotHaveSimpleName("package-info")
                .should().onlyDependOnClassesThat().resideInAnyPackage("com.nexusphere.shared..", "java..")
                .check(CLASSES);
    }

    @Test
    void domainDoesNotDependOnFrameworksOrOuterLayers() {
        ArchRule rule = noClasses().that().resideInAPackage("com.nexusphere.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "org.hibernate..", "tools.jackson..",
                        "com.fasterxml.jackson..", "com.nexusphere.*.application..", "com.nexusphere.*.api..",
                        "com.nexusphere.*.infrastructure..")
                .allowEmptyShould(true);
        rule.check(CLASSES);
    }

    @Test
    void applicationDoesNotDependOnAdapters() {
        noClasses().that().resideInAPackage("com.nexusphere.*.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.nexusphere.*.api..", "com.nexusphere.*.infrastructure..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @TestFactory
    Stream<DynamicTest> otherModulesUseOnlyTheContractPackage() {
        return MODULES.stream().map(module -> DynamicTest.dynamicTest(module, () ->
                noClasses().that().resideOutsideOfPackage("com.nexusphere." + module + "..")
                        .should().dependOnClassesThat().resideInAnyPackage(
                                "com.nexusphere." + module + ".domain..",
                                "com.nexusphere." + module + ".application..",
                                "com.nexusphere." + module + ".api..",
                                "com.nexusphere." + module + ".infrastructure..")
                        .allowEmptyShould(true)
                        .because("module " + module + " is reached only through its contract package")
                        .check(CLASSES)));
    }
}
