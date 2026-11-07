package io.github.cuukenn.fincalc.rule.script;

import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Loads {@code .fcs} external function scripts onto a {@link FunctionRegistry} (design §8.6,
 * Task 27).
 *
 * <p>{@link #load(List, FunctionRegistry)} reads the given files in sorted path order — later
 * files override same-name definitions — merges them into one definition set and hands it to
 * {@link FunctionRegistry#withDefinitions}, which compiles the bodies (forward references and
 * cross-file references resolve against the merged set), rejects recursion ({@code
 * FCS-RULE-3011}) and unauthorized built-in overrides ({@code FCS-RULE-3010}), inlines every
 * script-to-script call and returns a frozen registry.
 *
 * <p>{@link #loadClasspath(String, FunctionRegistry)} does the same for UTF-8 {@code .fcs}
 * resources under the given classpath prefix (e.g. {@code functions}), sorted by resource name —
 * the Task 29 startup wiring. v1 limitation, disclosed: resources are enumerated from
 * {@code file:} classpath directories only (exploded deployment); resources inside jars are not
 * enumerated. All script bytes are read as UTF-8 (global constraint).
 */
public final class FunctionScriptLoader {

    private FunctionScriptLoader() {
    }

    /**
     * Loads the given script files.
     *
     * @param files the {@code .fcs} files; loaded in sorted path order, later files override
     *              same-name definitions; never {@code null}
     * @param base  the registry the definitions build on (built-ins live here); never
     *              {@code null}
     * @return the frozen registry carrying every definition
     * @throws io.github.cuukenn.fincalc.common.error.BusinessException with {@code FCS-RULE-3004} for script
     *                                    syntax errors ({@code 文件:行号} attributed), the
     *                                    per-file definition limit, or a post-inlining depth
     *                                    overflow; {@code FCS-RULE-3010} for an unauthorized
     *                                    built-in override; {@code FCS-RULE-3011} for a
     *                                    recursive definition; the body parse codes
     *                                    (3001/3004/3009) for a bad body expression
     * @throws UncheckedIOException when a file cannot be read
     */
    public static FunctionRegistry load(List<Path> files, FunctionRegistry base) {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(base, "base");
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(null);
        Map<String, FunctionDefinition> merged = new LinkedHashMap<>();
        for (Path file : sorted) {
            Objects.requireNonNull(file, "files element");
            for (FunctionDefinition definition
                    : FunctionScriptParser.parse(read(file), sourceName(file))) {
                merged.put(definition.name(), definition);
            }
        }
        return base.withDefinitions(new ArrayList<>(merged.values()));
    }

    /**
     * Loads every {@code .fcs} resource under the given classpath prefix, in sorted resource
     * name order, later resources overriding same-name definitions.
     *
     * @param prefix classpath directory prefix, e.g. {@code functions}; never {@code null}
     * @param base   the registry the definitions build on; never {@code null}
     * @return the frozen registry carrying every definition; {@code base} itself when the prefix
     *         matches nothing
     */
    public static FunctionRegistry loadClasspath(String prefix, FunctionRegistry base) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(base, "base");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = FunctionScriptLoader.class.getClassLoader();
        }
        // Sorted map of resource name (relative to the prefix directory) -> file path.
        Map<String, Path> resources = new TreeMap<>();
        try {
            Enumeration<URL> roots = loader.getResources(prefix);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if (!"file".equals(root.getProtocol())) {
                    continue;
                }
                Path directory;
                try {
                    directory = Paths.get(root.toURI());
                } catch (URISyntaxException ex) {
                    throw new IOException("无效的类资源 URL: " + root, ex);
                }
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(directory)) {
                    walk.filter(Files::isRegularFile).forEach(path -> {
                        String name = directory.relativize(path).toString().replace('\\', '/');
                        if (name.endsWith(".fcs")) {
                            resources.putIfAbsent(name, path);
                        }
                    });
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("类路径函数脚本读取失败: " + prefix, ex);
        }
        Map<String, FunctionDefinition> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Path> resource : resources.entrySet()) {
            for (FunctionDefinition definition
                    : FunctionScriptParser.parse(read(resource.getValue()),
                    resource.getKey())) {
                merged.put(definition.name(), definition);
            }
        }
        return base.withDefinitions(new ArrayList<>(merged.values()));
    }

    private static String sourceName(Path file) {
        Path fileName = file.getFileName();
        return fileName != null ? fileName.toString() : file.toString();
    }

    private static String read(Path file) {
        try {
            return stripBom(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("函数脚本文件读取失败: " + file, ex);
        }
    }

    /**
     * Strips a leading UTF-8 BOM ({@code U+FEFF}, final-review fix wave): a script saved by a
     * Windows editor commonly carries one, and read as a character it would break the VERSION
     * check with a wrong-sounding syntax error instead of being the invisible marker it is.
     */
    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
