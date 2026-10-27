package net.monacraft.spellforge.arcsys.vm;

import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Instruction;
import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Op;
import net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Program;

import java.util.ArrayList;
import java.util.List;

/** Rejects malformed or externally corrupted bytecode before ArcVM execution. */
public final class ArcVerifier {
    public static final int MAX_CODE = 4_096;

    public List<Issue> verify(Program program) {
        List<Issue> issues = new ArrayList<>();
        if (program.instructions().isEmpty()) issues.add(new Issue("ARC-V4001", -1, "empty program"));
        if (program.instructions().size() > MAX_CODE) issues.add(new Issue("ARC-V4002", -1, "instruction limit exceeded"));
        for (var entry : program.entryPoints().entrySet()) {
            if (entry.getKey().isBlank() || !validTarget(entry.getValue(), program.instructions().size()))
                issues.add(new Issue("ARC-V4003", entry.getValue(), "invalid entry point"));
        }
        for (int pc = 0; pc < program.instructions().size(); pc++) {
            Instruction instruction = program.instructions().get(pc);
            if ((instruction.op() == Op.JUMP || instruction.op() == Op.JUMP_IF_FALSE)
                    && !validTarget(instruction.number(), program.instructions().size()))
                issues.add(new Issue("ARC-V4004", pc, "invalid jump target"));
            if (instruction.op() == Op.MAKE_LIST && (instruction.number() < 0 || instruction.number() > ArcVm.MAX_LIST))
                issues.add(new Issue("ARC-V4005", pc, "invalid list size"));
            if (instruction.op() == Op.CALL_HOST) {
                if (instruction.number() < 0 || instruction.number() > 32 || instruction.text().indexOf('\u0000') < 1)
                    issues.add(new Issue("ARC-V4006", pc, "invalid host descriptor"));
            }
            if (instruction.op() == Op.CALL) {
                Integer target = program.entryPoints().get("fn:" + instruction.text());
                if (target == null || !validTarget(target, program.instructions().size()) || instruction.number() < 0 || instruction.number() > 16)
                    issues.add(new Issue("ARC-V4008", pc, "invalid function call"));
            }
            if (instruction.text().length() > ArcVm.MAX_STRING)
                issues.add(new Issue("ARC-V4007", pc, "operand string too long"));
        }
        return List.copyOf(issues);
    }

    private static boolean validTarget(long target, int size) { return target >= 0 && target < size; }
    public record Issue(String code, int instruction, String message) { }
}
