package io.github.cuukenn.fincalc.domain.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The {@code FCS-CALC-*} codes sit in this enum so a code exists in exactly one place. These tests
 * pin the two halves of that claim: the §3.4 schedule-validation block is carried here, and no
 * production source outside this enum spells one of its literals.
 */
class CalcErrorCodeTest {

    /**
     * A quoted occurrence of one of the schedule-validation codes, e.g. {@code "FCS-CALC-3001"} or
     * {@code "FCS-CALC-3002: 末期余额 "}. Prose that names a code without quoting it, such as the
     * javadoc {@code {@code FCS-CALC-3001}}, is documentation rather than a literal and does not
     * match.
     */
    private static final Pattern ORPHAN_CODE_LITERAL = Pattern.compile("\"FCS-CALC-300[124]");

    @Test
    void carries_the_whole_schedule_validation_block() {
        assertThat(codes()).contains("FCS-CALC-3001", "FCS-CALC-3002", "FCS-CALC-3004");
    }

    @Test
    void declares_every_code_once() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    void keeps_the_schedule_validation_literals_out_of_production_sources() {
        Path mainSources = Path.of("src", "main", "java");
        assertThat(mainSources).as("surefire runs this test from the calc-domain module directory")
                .isDirectory();
        Path enumSource = mainSources.resolve(
                CalcErrorCode.class.getName().replace('.', '/') + ".java");

        List<String> orphans = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.equals(enumSource))
                    .forEach(path -> collectOrphans(path, orphans));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }

        assertThat(orphans)
                .as("FCS-CALC-3001/3002/3004 literals outside CalcErrorCode")
                .isEmpty();
    }

    private static List<String> codes() {
        return Arrays.stream(CalcErrorCode.values())
                .map(CalcErrorCode::code)
                .collect(Collectors.toList());
    }

    private static void collectOrphans(Path file, List<String> orphans) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
                continue;
            }
            if (ORPHAN_CODE_LITERAL.matcher(line).find()) {
                orphans.add(file + ":" + (index + 1) + "  " + line);
            }
        }
    }
}
