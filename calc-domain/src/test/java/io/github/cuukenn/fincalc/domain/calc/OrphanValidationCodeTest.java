package io.github.cuukenn.fincalc.domain.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * R127: this guards exactly the sites the task consolidated — the three validators that raise
 * {@code FCS-CALC-3001}/{@code FCS-CALC-3002}/{@code FCS-CALC-3004} and
 * {@code AbstractAmortizationGenerator#checkConservation}, which raises the same two — plus the enum
 * as their only home. It is a policy check over those located files, not a repo-wide claim: the
 * chain key a {@code Validator} answers is a different namespace, {@code calc-server}'s
 * {@code @Schema} example is documentation prose, and {@code FCS-ENG-*} codes belong to
 * {@code EngineErrorCode}.
 *
 * <p>The assertion names the literals it forbids, and the scan matches a <em>quoted</em> occurrence,
 * so a javadoc mention such as the enum constant link never collides with the claim. The scan
 * deliberately does not skip comment lines: none of the four sites needs to quote a code in prose,
 * so a quoted literal anywhere in them is a finding.
 */
class OrphanValidationCodeTest {

    /** A quoted occurrence of one of the three codes: {@code "FCS-CALC-3001"} or {@code "FCS-CALC-3001: …"}. */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("\"FCS-CALC-300[124]");

    /** The files this task consolidated, as paths below {@code src/main/java}. */
    private static final List<String> RAISING_SITES = List.of(
            "io/github/cuukenn/fincalc/domain/support/validate/PrincipalConservationValidator.java",
            "io/github/cuukenn/fincalc/domain/support/validate/TailBalanceValidator.java",
            "io/github/cuukenn/fincalc/domain/support/validate/DateMonotonicValidator.java",
            "io/github/cuukenn/fincalc/domain/support/AbstractAmortizationGenerator.java");

    @Test
    void the_consolidated_sites_spell_no_quoted_3001_3002_3004_literal() {
        Path mainSources = Path.of("src", "main", "java");
        assertThat(mainSources).as("surefire runs this test from the calc-domain module directory")
                .isDirectory();

        List<String> offenders = new ArrayList<>();
        for (String site : RAISING_SITES) {
            Path file = mainSources.resolve(site);
            assertThat(file).as("consolidated raising site").isRegularFile();
            collectQuotedLiterals(file, offenders);
        }

        assertThat(offenders)
                .as("quoted FCS-CALC-3001/3002/3004 literals in the consolidated sites; "
                        + "CalcErrorCode is their only home")
                .isEmpty();
    }

    private static void collectQuotedLiterals(Path file, List<String> offenders) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        for (int index = 0; index < lines.size(); index++) {
            if (QUOTED_LITERAL.matcher(lines.get(index)).find()) {
                offenders.add(file + ":" + (index + 1) + "  " + lines.get(index).trim());
            }
        }
    }
}
