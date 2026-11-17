package moe.ryuu.kyokouauth.geyser.avatar;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deliberately bounded flat-object wire schema; no recursive JSON objects/arrays or dependencies. */
final class AvatarJson {
    private final String text;
    private int at;
    private AvatarJson(byte[] bytes) {
        if (bytes.length > AvatarSnapshot.MAX_JSON_BYTES) throw new IllegalArgumentException("Avatar JSON exceeds limit");
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Avatar JSON is not UTF-8"); }
    }

    static Map<String, Object> object(byte[] bytes) {
        AvatarJson parser = new AvatarJson(bytes);
        Map<String, Object> result = new LinkedHashMap<>();
        parser.expect('{');
        if (!parser.take('}')) {
            do {
                String key = parser.string(32);
                parser.expect(':');
                Object value;
                parser.space();
                if (parser.peek() == '"') value = parser.string(11_000);
                else if (parser.literal("true")) value = true;
                else if (parser.literal("false")) value = false;
                else if (parser.literal("null")) value = null;
                else {
                    int begin = parser.at;
                    while (parser.at < parser.text.length() && Character.isDigit(parser.text.charAt(parser.at))) parser.at++;
                    String number = parser.text.substring(begin, parser.at);
                    if (!number.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("Invalid avatar JSON number");
                    value = Long.parseLong(number);
                }
                if (result.containsKey(key) || result.size() >= 8) throw new IllegalArgumentException("Duplicate or excessive avatar JSON fields");
                result.put(key, value);
            } while (parser.take(','));
            parser.expect('}');
        }
        parser.space();
        if (parser.at != parser.text.length()) throw new IllegalArgumentException("Trailing avatar JSON");
        return result;
    }

    private String string(int limit) {
        expect('"');
        StringBuilder value = new StringBuilder();
        boolean ended = false;
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') { ended = true; break; }
            if (c < 32) throw new IllegalArgumentException("Control character in avatar JSON");
            if (c == '\\') {
                if (at >= text.length()) throw new IllegalArgumentException("Truncated avatar JSON escape");
                c = text.charAt(at++);
                c = switch (c) {
                    case '"', '\\', '/' -> c;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    case 'u' -> {
                        if (at + 4 > text.length() || !text.substring(at, at + 4).matches("[0-9a-fA-F]{4}"))
                            throw new IllegalArgumentException("Invalid avatar JSON Unicode escape");
                        char decoded = (char) Integer.parseInt(text.substring(at, at + 4), 16);
                        at += 4;
                        yield decoded;
                    }
                    default -> throw new IllegalArgumentException("Invalid avatar JSON escape");
                };
            }
            if (value.length() >= limit) throw new IllegalArgumentException("Avatar JSON string exceeds limit");
            value.append(c);
        }
        if (!ended) throw new IllegalArgumentException("Unterminated avatar JSON string");
        return value.toString();
    }
    private char peek() { return at < text.length() ? text.charAt(at) : 0; }
    private void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
    private boolean take(char c) { space(); if (peek() == c) { at++; return true; } return false; }
    private void expect(char c) { if (!take(c)) throw new IllegalArgumentException("Invalid avatar JSON structure"); }
    private boolean literal(String value) {
        if (!text.startsWith(value, at)) return false;
        at += value.length();
        return true;
    }
}
