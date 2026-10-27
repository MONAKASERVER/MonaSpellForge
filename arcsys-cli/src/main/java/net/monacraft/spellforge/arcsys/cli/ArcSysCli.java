package net.monacraft.spellforge.arcsys.cli;

import net.monacraft.spellforge.arcsys.compiler.ArcCompiler;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline checker/disassembler. It never executes host code or contacts a server. */
public final class ArcSysCli {
    private ArcSysCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("check") || args[0].equals("compile") || args[0].equals("disasm"))) {
            System.err.println("Usage: arcsys <check|compile|disasm> <file.arc>");
            System.exit(2);
        }
        Path sourcePath = Path.of(args[1]);
        var result = new ArcCompiler().compile(Files.readString(sourcePath));
        result.diagnostics().forEach(d -> System.err.printf("%s %s:%d:%d %s%n", d.severity(), d.code(), d.span().start().line(), d.span().start().column(), d.message()));
        if (!result.valid()) System.exit(1);
        System.out.printf("OK %s fuel=%d instructions=%d sha256=%s%n", result.program().spellName(), result.program().estimatedFuel(), result.program().instructions().size(), result.program().sha256());
        if (args[0].equals("compile")) {
            Path output = sourcePath.resolveSibling(sourcePath.getFileName() + "b");
            Files.write(output, result.program().encode());
            System.out.println("Wrote " + output.getFileName());
        } else if (args[0].equals("disasm")) {
            for (int i = 0; i < result.program().instructions().size(); i++) System.out.printf("%04d %s%n", i, result.program().instructions().get(i));
        }
    }
}
