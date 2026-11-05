// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 JSON reader and minimal writer for update metadata. Duplicate object keys, trailing
 * content, comments and excessive nesting are rejected so that a document has exactly one meaning.
 */
final class Json {
    static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;
        JsonException(String message) { super(message); }
    }

    private static final int MAX_DEPTH = 32;
    private final String text;
    private int position;

    private Json(String text) { this.text = text; }

    static Object parse(String text) throws JsonException {
        Json parser = new Json(text);
        parser.whitespace();
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.position != text.length()) throw parser.error("Unexpected content after the JSON value");
        return value;
    }

    private Object value(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw error("JSON nesting is too deep");
        if (position >= text.length()) throw error("Unexpected end of JSON");
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || c >= '0' && c <= '9') yield number();
                throw error("Unexpected character");
            }
        };
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> result = new LinkedHashMap<>();
        position++;
        whitespace();
        if (peek('}')) { position++; return result; }
        while (true) {
            whitespace();
            if (!peek('"')) throw error("Expected a member name");
            String key = string();
            whitespace();
            expect(':');
            whitespace();
            if (result.containsKey(key)) throw error("Duplicate member \"" + key + "\"");
            result.put(key, value(depth + 1));
            whitespace();
            if (peek(',')) { position++; continue; }
            expect('}');
            return result;
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> result = new ArrayList<>();
        position++;
        whitespace();
        if (peek(']')) { position++; return result; }
        while (true) {
            whitespace();
            result.add(value(depth + 1));
            whitespace();
            if (peek(',')) { position++; continue; }
            expect(']');
            return result;
        }
    }

    private String string() throws JsonException {
        position++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= text.length()) throw error("Unterminated string");
            char c = text.charAt(position++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error("Control character in string");
            if (c != '\\') { out.append(c); continue; }
            if (position >= text.length()) throw error("Unterminated escape");
            char e = text.charAt(position++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) throw error("Truncated unicode escape");
                    try { out.append((char) Integer.parseInt(text.substring(position, position + 4), 16)); }
                    catch (NumberFormatException ex) { throw error("Invalid unicode escape"); }
                    position += 4;
                }
                default -> throw error("Invalid escape");
            }
        }
    }

    private Object literal(String word, Object value) throws JsonException {
        if (!text.startsWith(word, position)) throw error("Invalid literal");
        position += word.length();
        return value;
    }

    private BigDecimal number() throws JsonException {
        int start = position;
        if (peek('-')) position++;
        if (peek('0')) position++;
        else if (digit()) while (digit()) position++;
        else throw error("Invalid number");
        if (peek('.')) { position++; if (!digit()) throw error("Invalid number"); while (digit()) position++; }
        if (peek('e') || peek('E')) {
            position++;
            if (peek('+') || peek('-')) position++;
            if (!digit()) throw error("Invalid number");
            while (digit()) position++;
        }
        if (position - start > 40) throw error("Number is too long");
        return new BigDecimal(text.substring(start, position));
    }

    private boolean digit() { return position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9'; }
    private boolean peek(char c) { return position < text.length() && text.charAt(position) == c; }
    private void expect(char c) throws JsonException { if (!peek(c)) throw error("Expected '" + c + "'"); position++; }
    private void whitespace() { while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++; }
    private JsonException error(String message) { return new JsonException(message + " at offset " + position + "."); }

    // Typed access helpers.

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String what) throws JsonException {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new JsonException(what + " must be a JSON object.");
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value, String what) throws JsonException {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new JsonException(what + " must be a JSON array.");
    }

    static String string(Map<String, Object> map, String key, boolean required) throws JsonException {
        Object value = map.get(key);
        if (value == null) { if (required) throw new JsonException("\"" + key + "\" is required."); return null; }
        if (value instanceof String s) return s;
        throw new JsonException("\"" + key + "\" must be a string.");
    }

    static boolean bool(Map<String, Object> map, String key) throws JsonException {
        if (map.get(key) instanceof Boolean b) return b;
        throw new JsonException("\"" + key + "\" must be true or false.");
    }

    static long integer(Map<String, Object> map, String key) throws JsonException {
        if (map.get(key) instanceof BigDecimal number) {
            try { return number.longValueExact(); }
            catch (ArithmeticException ex) { throw new JsonException("\"" + key + "\" must be a whole number."); }
        }
        throw new JsonException("\"" + key + "\" must be a number.");
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
