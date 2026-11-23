package opentrap.ui;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;

/**
 * Builds the "copy as curl command" text. Every part (method, header, body, URL)
 * goes through {@link #quote}, which emits plain single quotes for printable
 * ASCII and {@code $'\xhh'} for every other byte, so bash and zsh read back the
 * exact bytes and the command stays on one printable line. The body goes out as
 * {@code --data-raw}, which unlike {@code --data} never reads a file for a
 * leading {@code @}; a body holding a NUL byte cannot be an argument, so it is
 * piped in with {@code printf} and {@code --data-binary @-}. A chunked body is
 * passed with its chunk framing (see PLAN.md).
 */
public final class CurlCommand {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CurlCommand() {}

    /** A shell command that reproduces {@code request} to {@code service}; empty for no request. */
    public static String of(HttpService service, HttpMessage request) {
        if (request == null) return "";
        String body = Bytes.str(request.body());
        boolean nul = body.indexOf('\u0000') >= 0;
        StringBuilder b = new StringBuilder();
        if (nul) b.append("printf ").append(printfFormat(body)).append(" | ");
        b.append("curl -i -s -k -g --path-as-is ");
        if (request.method().equals("HEAD")) b.append("--head ");
        else b.append("-X ").append(quote(request.method())).append(' ');
        for (String line : request.headerLines()) {
            int c = line.indexOf(':');
            if (c <= 0 || line.charAt(0) == '@') continue;
            String name = line.substring(0, c).trim();
            if (name.equalsIgnoreCase("content-length")) continue;
            String value = line.substring(c + 1).trim();
            b.append("-H ").append(quote(value.isEmpty() ? name + ";" : line)).append(' ');
        }
        if (nul) b.append("--data-binary @- ");
        else if (!body.isEmpty()) b.append("--data-raw ").append(quote(body)).append(' ');
        b.append(quote(url(service, request.requestTarget())));
        return b.toString();
    }

    /** One shell word that bash and zsh read back as exactly {@code s} (ISO-8859-1, one char per byte). */
    static String quote(String s) {
        if (s.isEmpty()) return "''";
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                b.append("\\'");
                i++;
            } else if (printable(c)) {
                b.append('\'');
                while (i < s.length() && printable(s.charAt(i)) && s.charAt(i) != '\'') b.append(s.charAt(i++));
                b.append('\'');
            } else {
                b.append("$'");
                while (i < s.length() && !printable(s.charAt(i))) {
                    char h = s.charAt(i++);
                    b.append("\\x").append(HEX[(h >> 4) & 0xf]).append(HEX[h & 0xf]);
                }
                b.append('\'');
            }
        }
        return b.toString();
    }

    /** The absolute URL for a request target, by the rule of {@code LogEntry.url()}. */
    static String url(HttpService service, String target) {
        if (target.startsWith("http://") || target.startsWith("https://")) return target;
        if (service == null) return target;
        if (target.isEmpty()) return service.url();
        return service.url() + (target.startsWith("/") ? target : "/" + target);
    }

    private static boolean printable(char c) {
        return c >= 0x20 && c <= 0x7e;
    }

    /** A single-quoted printf format that prints {@code body} byte for byte. */
    private static String printfFormat(String body) {
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') b.append("'\\''");
            else if (c == '%') b.append("%%");
            else if (c == '\\') b.append("\\\\");
            else if (printable(c)) b.append(c);
            else b.append('\\').append((char) ('0' + ((c >> 6) & 7))).append((char) ('0' + ((c >> 3) & 7))).append((char) ('0' + (c & 7)));
        }
        return b.append('\'').toString();
    }
}
