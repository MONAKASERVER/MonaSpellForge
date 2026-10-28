package net.monacraft.spellforge.arcsys.compiler;

import net.monacraft.spellforge.arcsys.language.ArcAst;
import net.monacraft.spellforge.arcsys.language.ArcAst.*;
import net.monacraft.spellforge.arcsys.language.ArcParser;
import net.monacraft.spellforge.arcsys.language.SourceModel.Diagnostic;
import net.monacraft.spellforge.arcsys.language.SourceModel.Severity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, deterministic linker for ArcSys module/use projects. */
public final class ArcProjectCompiler {
    public static final int MAX_MODULES = 32;
    public static final int MAX_PROJECT_CHARS = 524_288;
    public static final String BUNDLE_MARKER = "// @arcsys-project-v1 ";

    public record ProjectBundle(String rootModule, Map<String, String> modules) {
        public ProjectBundle { modules = Map.copyOf(modules); }
    }

    public ArcCompiler.CompileResult compileStored(String storedSource) {
        if (!storedSource.startsWith(BUNDLE_MARKER)) return new ArcCompiler().compile(storedSource);
        ProjectBundle bundle = decodeBundle(storedSource);
        return compile(bundle.modules(), bundle.rootModule());
    }

    public static String encodeBundle(Map<String, String> sources, String rootModule) {
        if (sources.isEmpty() || sources.size() > MAX_MODULES || sources.values().stream().mapToInt(String::length).sum() > MAX_PROJECT_CHARS)
            throw new IllegalArgumentException("project bounds");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            write(out, rootModule);
            List<Map.Entry<String, String>> modules = sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
            out.writeInt(modules.size());
            for (var module : modules) { write(out, module.getKey()); write(out, module.getValue()); }
            out.flush();
            return BUNDLE_MARKER + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static ProjectBundle decodeBundle(String source) {
        if (!source.startsWith(BUNDLE_MARKER)) throw new IllegalArgumentException("not an ArcSys project bundle");
        byte[] payload;
        try { payload = Base64.getUrlDecoder().decode(source.substring(BUNDLE_MARKER.length()).trim()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid ArcSys project bundle"); }
        if (payload.length > MAX_PROJECT_CHARS * 2) throw new IllegalArgumentException("project bundle too large");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload)); String root = read(in);
            int count = in.readInt(); if (count < 1 || count > MAX_MODULES) throw new IllegalArgumentException("module count");
            Map<String, String> modules = new LinkedHashMap<>(); int characters = 0;
            for (int i = 0; i < count; i++) {
                String name = read(in), text = read(in); characters += text.length();
                if (modules.putIfAbsent(name, text) != null || characters > MAX_PROJECT_CHARS) throw new IllegalArgumentException("invalid modules");
            }
            if (in.available() != 0) throw new IllegalArgumentException("trailing project data");
            return new ProjectBundle(root, modules);
        } catch (IOException error) { throw new IllegalArgumentException("truncated ArcSys project bundle", error); }
    }

    public ArcCompiler.CompileResult compile(Map<String, String> sources, String rootModule) {
        Objects.requireNonNull(sources); Objects.requireNonNull(rootModule);
        if (sources.isEmpty() || sources.size() > MAX_MODULES || sources.values().stream().mapToInt(String::length).sum() > MAX_PROJECT_CHARS)
            return failure("ARC-M6000", "module数またはprojectサイズが上限を超えています", null);

        Map<String, ArcAst.Program> modules = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (var source : sources.entrySet()) {
            var parsed = ArcParser.parse(source.getValue()); diagnostics.addAll(parsed.diagnostics());
            String fallback = normalizeModule(source.getKey());
            String name = parsed.program().module() == null ? fallback : parsed.program().module().name();
            if (modules.putIfAbsent(name, parsed.program()) != null)
                diagnostics.add(new Diagnostic("ARC-M6001", Severity.ERROR, "module名が重複しています: " + name,
                        parsed.program().span(), "module名を一意にしてください"));
        }
        if (diagnostics.stream().anyMatch(value -> value.severity() == Severity.ERROR))
            return new ArcCompiler.CompileResult(null, diagnostics);
        ArcAst.Program root = modules.get(rootModule);
        if (root == null) root = modules.get(normalizeModule(rootModule));
        if (root == null) return failure("ARC-M6002", "root moduleが見つかりません: " + rootModule, modules.values().stream().findFirst().orElse(null));

        Map<String, Exports> memo = new HashMap<>();
        Exports linked = resolve(moduleName(root, rootModule), root, modules, memo, new ArrayDeque<>(), diagnostics);
        if (linked == null || diagnostics.stream().anyMatch(value -> value.severity() == Severity.ERROR))
            return new ArcCompiler.CompileResult(null, diagnostics);
        if (root.spells().size() != 1) return failure("ARC-M6003", "root moduleにはspellを1つ定義してください", root);

        SpellDecl original = root.spells().getFirst();
        List<VarDecl> states = concat(linked.constants(), original.states());
        List<FunctionDecl> functions = concat(linked.functions(), original.functions());
        SpellDecl spell = new SpellDecl(original.name(), original.requiredFragments(), original.requiredPermissions(),
                states, original.handlers(), functions, original.preconditions(), original.postconditions(), original.span());
        List<TypeDecl> types = concat(linked.types(), root.types());
        ArcAst.Program program = new ArcAst.Program(root.module(), List.of(), distinctTypes(types, diagnostics),
                List.of(), List.of(), List.of(spell), root.span());
        if (diagnostics.stream().anyMatch(value -> value.severity() == Severity.ERROR))
            return new ArcCompiler.CompileResult(null, diagnostics);
        Map<ArcAst.Node, String> provenance = new IdentityHashMap<>();
        modules.forEach((module, source) -> {
            source.types().forEach(node -> provenance.put(node, module));
            source.globalConstants().forEach(node -> provenance.put(node, module));
            source.globalFunctions().forEach(node -> provenance.put(node, module));
            source.spells().forEach(node -> provenance.put(node, module));
        });
        ArcCompiler.CompileResult compiled = new ArcCompiler().compile(program, provenance);
        diagnostics.addAll(compiled.diagnostics());
        return new ArcCompiler.CompileResult(compiled.program(), diagnostics);
    }

    private Exports resolve(String name, ArcAst.Program program, Map<String, ArcAst.Program> modules,
                            Map<String, Exports> memo, Deque<String> stack, List<Diagnostic> diagnostics) {
        if (memo.containsKey(name)) return memo.get(name);
        if (stack.contains(name)) {
            diagnostics.add(new Diagnostic("ARC-M6004", Severity.ERROR, "循環importです: " + String.join(" -> ", stack) + " -> " + name,
                    program.span(), "module依存を循環しない構造へ変更してください")); return null;
        }
        stack.addLast(name);
        List<TypeDecl> types = new ArrayList<>(); List<VarDecl> constants = new ArrayList<>(); List<FunctionDecl> functions = new ArrayList<>();
        for (UseDecl use : program.imports()) {
            ArcAst.Program dependency = modules.get(use.module());
            if (dependency == null) {
                diagnostics.add(new Diagnostic("ARC-M6005", Severity.ERROR, "import先が見つかりません: " + use.module(), use.span(), "projectへmoduleを追加してください"));
                continue;
            }
            Exports child = resolve(use.module(), dependency, modules, memo, stack, diagnostics);
            if (child == null) continue;
            types.addAll(child.types()); constants.addAll(child.constants()); functions.addAll(child.functions());
            types.addAll(dependency.types()); constants.addAll(dependency.globalConstants()); functions.addAll(dependency.globalFunctions());
            if (use.alias() != null) {
                for (TypeDecl type : dependency.types()) types.add(aliasType(use.alias(), type));
                for (FunctionDecl function : dependency.globalFunctions()) functions.add(aliasFunction(use.alias(), function));
            }
        }
        stack.removeLast();
        Exports exports = new Exports(List.copyOf(types), List.copyOf(constants), List.copyOf(functions)); memo.put(name, exports); return exports;
    }

    private TypeDecl aliasType(String alias, TypeDecl type) {
        if (type instanceof StructDecl struct) return new StructDecl(alias + "." + struct.name(), struct.fields(), struct.span());
        EnumDecl enumeration = (EnumDecl) type;
        return new EnumDecl(alias + "." + enumeration.name(), enumeration.variants(), enumeration.span());
    }

    private FunctionDecl aliasFunction(String alias, FunctionDecl function) {
        List<CallArg> arguments = function.parameters().stream()
                .map(name -> new CallArg(null, new Variable(name, function.span()), function.span())).toList();
        Call call = new Call(new Variable(function.name(), function.span()), arguments, function.span());
        Block body = new Block(List.of(new ReturnStmt(call, function.span())), function.span());
        return new FunctionDecl(alias + "." + function.name(), function.parameters(), function.parameterTypes(),
                function.returnType(), function.effectful(), function.declaredEffects(), body, function.span());
    }

    private List<TypeDecl> distinctTypes(List<TypeDecl> values, List<Diagnostic> diagnostics) {
        Map<String, TypeDecl> result = new LinkedHashMap<>();
        for (TypeDecl value : values) if (result.putIfAbsent(value.name(), value) != null)
            diagnostics.add(new Diagnostic("ARC-M6006", Severity.ERROR, "importした型名が衝突しています: " + value.name(), value.span(), "aliasまたは型名を変更してください"));
        return List.copyOf(result.values());
    }

    private ArcCompiler.CompileResult failure(String code, String message, ArcAst.Program source) {
        var span = source == null ? new net.monacraft.spellforge.arcsys.language.SourceModel.Span(
                new net.monacraft.spellforge.arcsys.language.SourceModel.Position(0, 1, 1),
                new net.monacraft.spellforge.arcsys.language.SourceModel.Position(0, 1, 1)) : source.span();
        return new ArcCompiler.CompileResult(null, List.of(new Diagnostic(code, Severity.ERROR, message, span, "module設定を確認してください")));
    }

    private String moduleName(ArcAst.Program value, String fallback) { return value.module() == null ? normalizeModule(fallback) : value.module().name(); }
    private static String normalizeModule(String value) {
        String normalized = value.replace('\\', '.').replace('/', '.');
        if (normalized.endsWith(".arc")) normalized = normalized.substring(0, normalized.length() - 4);
        return normalized.replaceAll("^\\.+|\\.+$", "");
    }
    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PROJECT_CHARS) throw new IllegalArgumentException("project text too large");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String read(DataInputStream in) throws IOException {
        int length = in.readInt(); if (length < 0 || length > MAX_PROJECT_CHARS) throw new IllegalArgumentException("invalid project text");
        byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static <T> List<T> concat(List<T> first, List<T> second) { List<T> result = new ArrayList<>(first); result.addAll(second); return List.copyOf(result); }
    private record Exports(List<TypeDecl> types, List<VarDecl> constants, List<FunctionDecl> functions) { }
}
