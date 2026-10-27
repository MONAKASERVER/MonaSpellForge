package net.monacraft.spellforge.arcsys.language;

import java.util.List;

public final class SourceModel {
    private SourceModel() { }

    public record Position(int offset, int line, int column) {
        public Position {
            if (offset < 0 || line < 1 || column < 1) throw new IllegalArgumentException("Invalid source position");
        }
    }

    public record Span(Position start, Position end) {
        public Span {
            if (start == null || end == null || end.offset() < start.offset()) throw new IllegalArgumentException("Invalid source span");
        }
    }

    public record Diagnostic(String code, Severity severity, String message, Span span, String suggestion) {
        public Diagnostic {
            suggestion = suggestion == null ? "" : suggestion;
        }
    }

    public enum Severity { ERROR, WARNING }

    public enum Unit { TICKS, BLOCKS, MANA, PERCENT, DAYS }

    public record UnitValue(double value, Unit unit) {
        public UnitValue {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid unit value");
        }
        public long ticks() {
            return switch (unit) {
                case TICKS -> Math.round(value);
                case DAYS -> Math.round(value * 24 * 60 * 60 * 20);
                default -> throw new IllegalStateException("Not a duration");
            };
        }
    }

    public record ParseResult(ArcAst.Program program, List<Diagnostic> diagnostics) {
        public ParseResult { diagnostics = List.copyOf(diagnostics); }
        public boolean valid() { return program != null && diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }
    }
}
