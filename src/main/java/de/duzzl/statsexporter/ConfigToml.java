package de.duzzl.statsexporter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The small TOML subset used by statsexporter.toml: tables, strings, integers,
 * booleans and string arrays. Keeping this local avoids shipping a parser and
 * its grammar runtime with every server installation. */
final class ConfigToml {
    private ConfigToml() { }

    static Map<String, Object> parse(String source) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> table = root;
        StringBuilder pending = new StringBuilder();
        int pendingLine = 0;
        String[] lines = source.replaceFirst("^\\uFEFF", "").split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = withoutComment(lines[i]).trim();
            if (line.isEmpty()) continue;
            if (pending.length() != 0) {
                pending.append(' ').append(line);
                if (!complete(pending.toString())) continue;
                line = pending.toString();
                pending.setLength(0);
            } else if (!complete(line)) {
                pending.append(line);
                pendingLine = i + 1;
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String name = line.substring(1, line.length() - 1).trim();
                if (!name.equals("dashboard") && !name.equals("dashboard.labels"))
                    throw error(i + 1, "unsupported table: " + name);
                table = root;
                for (String part : name.split("\\.")) {
                    Object existing = table.get(part);
                    if (existing == null) {
                        existing = new LinkedHashMap<String, Object>();
                        table.put(part, existing);
                    }
                    if (!(existing instanceof Map<?, ?>)) throw error(i + 1, "table conflicts with a value");
                    @SuppressWarnings("unchecked") Map<String, Object> next = (Map<String, Object>) existing;
                    table = next;
                }
                continue;
            }
            int equals = outsideQuotes(line, '=');
            if (equals < 0) throw error(i + 1, "expected key = value");
            String key = key(line.substring(0, equals).trim(), i + 1);
            if (table.containsKey(key)) throw error(i + 1, "duplicate key: " + key);
            table.put(key, value(line.substring(equals + 1).trim(), i + 1));
        }
        if (pending.length() != 0) throw error(pendingLine, "unfinished value");
        return root;
    }

    private static String withoutComment(String line) {
        boolean quoted = false, literal = false, escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && !literal && c == '\\') { escaped = true; continue; }
            if (quoted && c == (literal ? '\'' : '"')) { quoted = false; continue; }
            if (!quoted && (c == '"' || c == '\'')) { quoted = true; literal = c == '\''; continue; }
            if (!quoted && c == '#') return line.substring(0, i);
        }
        return line;
    }

    private static boolean complete(String line) {
        boolean quoted = false, literal = false, escaped = false;
        int brackets = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && !literal && c == '\\') { escaped = true; continue; }
            if (quoted && c == (literal ? '\'' : '"')) { quoted = false; continue; }
            if (!quoted && (c == '"' || c == '\'')) { quoted = true; literal = c == '\''; continue; }
            if (!quoted && c == '[') brackets++;
            if (!quoted && c == ']') brackets--;
        }
        return !quoted && brackets <= 0;
    }

    private static int outsideQuotes(String text, char wanted) {
        boolean quoted = false, literal = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && !literal && c == '\\') { escaped = true; continue; }
            if (quoted && c == (literal ? '\'' : '"')) { quoted = false; continue; }
            if (!quoted && (c == '"' || c == '\'')) { quoted = true; literal = c == '\''; continue; }
            if (!quoted && c == wanted) return i;
        }
        return -1;
    }

    private static String key(String text, int line) throws IOException {
        if (text.startsWith("\"") || text.startsWith("'")) {
            Object parsed = value(text, line);
            if (parsed instanceof String string && !string.isEmpty()) return string;
        } else if (text.matches("[A-Za-z0-9_-]+")) return text;
        throw error(line, "invalid key");
    }

    private static Object value(String text, int line) throws IOException {
        if (text.startsWith("\"") || text.startsWith("'")) return string(text, line);
        if (text.startsWith("[") && text.endsWith("]")) {
            String inner = text.substring(1, text.length() - 1).trim();
            List<Object> result = new ArrayList<>();
            if (inner.isEmpty()) return result;
            int start = 0;
            while (start < inner.length()) {
                int comma = outsideQuotes(inner.substring(start), ',');
                int end = comma < 0 ? inner.length() : start + comma;
                String part = inner.substring(start, end).trim();
                if (part.isEmpty()) throw error(line, "empty array item");
                result.add(value(part, line));
                if (comma < 0) break;
                start = end + 1;
            }
            return result;
        }
        if (text.equals("true")) return true;
        if (text.equals("false")) return false;
        if (text.matches("[+-]?(0|[1-9](?:[0-9]|_[0-9])*)")) {
            try { return Long.parseLong(text.replace("_", "")); }
            catch (NumberFormatException e) { throw error(line, "integer out of range"); }
        }
        throw error(line, "unsupported or invalid value");
    }

    private static String string(String text, int line) throws IOException {
        char quote = text.charAt(0);
        if (text.length() < 2 || text.charAt(text.length() - 1) != quote) throw error(line, "unterminated string");
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == quote) throw error(line, "unescaped quote");
            if (c != '\\' || quote == '\'') { result.append(c); continue; }
            if (++i >= text.length() - 1) throw error(line, "unfinished escape");
            c = text.charAt(i);
            switch (c) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case '"', '\\' -> result.append(c);
                case 'u', 'U' -> {
                    int digits = c == 'u' ? 4 : 8;
                    if (i + digits >= text.length()) throw error(line, "unfinished Unicode escape");
                    try {
                        int codePoint = Integer.parseInt(text.substring(i + 1, i + 1 + digits), 16);
                        result.appendCodePoint(codePoint);
                    } catch (IllegalArgumentException e) { throw error(line, "invalid Unicode escape"); }
                    i += digits;
                }
                default -> throw error(line, "invalid escape: " + c);
            }
        }
        return result.toString();
    }

    private static IOException error(int line, String message) { return new IOException("line " + line + ": " + message); }
}
