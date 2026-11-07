package io.github.cuukenn.fincalc.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DependencyDirectionTest {
    private final JavaClasses classes =
            new ClassFileImporter().importPackages("io.github.cuukenn.fincalc");

    @Test
    void domain_must_not_depend_on_frameworks() {
        ArchRule rule = noClasses().that().resideInAPackage("io.github.cuukenn.fincalc.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "lombok..", "org.springframework..", "org.apache.poi..",
                        "org.apache.ibatis..", "com.fasterxml.jackson..");
        rule.check(classes);
    }

    @Test
    void domain_may_only_depend_on_domain_and_common() {
        // 箭头只能指向 domain 与 common：domain 内的类不得依赖任何其他 io.github.cuukenn.fincalc 包
        //（JDK 类型不受限）。其他模块尚未建立，建立后本规则自动覆盖它们。
        ArchRule rule = noClasses().that().resideInAPackage("io.github.cuukenn.fincalc.domain..")
                .should().dependOnClassesThat(resideInAPackage("io.github.cuukenn.fincalc..")
                        .and(resideOutsideOfPackages("io.github.cuukenn.fincalc.domain..", "io.github.cuukenn.fincalc.common..")));
        rule.check(classes);
    }
}
