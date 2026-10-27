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

    public record ParseResult(ArcAst.Program program, List<Diagnostic> diagnostics) {
        public ParseResult { diagnostics = List.copyOf(diagnostics); }
        public boolean valid() { return program != null && diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }
    }
}
