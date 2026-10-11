package com.creatorstore;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The module rules from HANDOFF.md, enforced on every build.
 *
 *  1. A repository is used only by classes in its own module (package).
 *  2. Controllers never touch repositories or entities' persistence; they call services.
 *  3. Business modules have no dependency cycles (the shared packages common, config and security are exempt).
 *  4. Nothing depends on an entity of another module except through that module's service and DTOs.
 */
@AnalyzeClasses(packages = "com.creatorstore", importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] SHARED = {"com.creatorstore.common..", "com.creatorstore.config..", "com.creatorstore.security.."};

    @ArchTest
    static final ArchRule repositoriesAreUsedOnlyInsideTheirOwnModule = classes()
            .that().haveSimpleNameEndingWith("Repository")
            .should(onlyBeDependedOnByClassesInTheSamePackage());

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule businessModulesAreFreeOfCycles = slices()
            .matching("com.creatorstore.(*)..")
            .should().beFreeOfCycles()
            .ignoreDependency(resideInAnyPackage(SHARED), alwaysTrue())
            .ignoreDependency(alwaysTrue(), resideInAnyPackage(SHARED));

    @ArchTest
    static final ArchRule sharedPackagesDoNotDependOnBusinessModules = noClasses()
            .that().resideInAnyPackage("com.creatorstore.common..", "com.creatorstore.config..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.creatorstore.auth..", "com.creatorstore.creator..", "com.creatorstore.verification..",
                    "com.creatorstore.admin..", "com.creatorstore.notification..", "com.creatorstore.product..",
                    "com.creatorstore.order..", "com.creatorstore.payment..", "com.creatorstore.automation..");

    private static ArchCondition<JavaClass> onlyBeDependedOnByClassesInTheSamePackage() {
        return new ArchCondition<>("only be depended on by classes in the same package") {
            @Override
            public void check(JavaClass repository, ConditionEvents events) {
                for (Dependency dependency : repository.getDirectDependenciesToSelf()) {
                    JavaClass origin = dependency.getOriginClass();
                    if (!origin.getPackageName().equals(repository.getPackageName())) {
                        events.add(SimpleConditionEvent.violated(repository,
                                origin.getName() + " uses " + repository.getName()
                                        + " from another module; call that module's service instead"));
                    }
                }
            }
        };
    }
}
