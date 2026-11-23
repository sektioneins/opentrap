package opentrap.ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Tests the copy-as-curl builder by running its output through real shells with
 * a stand-in curl that prints each argument NUL-terminated, so the arguments
 * that curl would receive can be compared byte for byte.
 */
public final class CurlCommandTest {

    private static final String FAKE =
            "curl() { for a; do printf '%s\\0' \"$a\"; done; "
            + "case \" $* \" in *\" @- \"*) printf 'STDIN\\0'; cat;; esac; }; ";

    private static final HttpService SVC = new HttpService("t.example", 443, true);

    private static HttpMessage req(String raw) {
        return new HttpMessage(Bytes.bytes(raw));
    }

    private static List<String> args(String shell, String command) {
        try {
            return run(shell, command);
        } catch (Exception e) {
            throw new IllegalStateException(shell + ": " + e, e);
        }
    }

    private static List<String> run(String shell, String command) throws Exception {
        Process p = new ProcessBuilder(shell, "-c", FAKE + command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(out);
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(shell + " did not finish");
        }
        String s = new String(out.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        List<String> list = new ArrayList<>(List.of(s.split("\0", -1)));
        if (!list.isEmpty() && list.get(list.size() - 1).isEmpty()) list.remove(list.size() - 1);
        return list;
    }

    private static String after(List<String> a, String flag) {
        int i = a.indexOf(flag);
        return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : null;
    }

    private static List<String> headerValues(List<String> a) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i + 1 < a.size(); i++) if (a.get(i).equals("-H")) r.add(a.get(i + 1));
        return r;
    }

    private static String last(List<String> a) {
        return a.get(a.size() - 1);
    }

    private static String allBytes() {
        StringBuilder b = new StringBuilder();
        for (char c = 1; c <= 0xff; c++) if (c != '\n' && c != '\r') b.append(c);
        return b.append("\\n\\x41").toString();
    }

    private static HttpMessage controlMessage() {
        return req("POST /x HTTP/1.1\r\nX-B: a\tb\u00e9\\x41\r\n\r\n" + allBytes());
    }

    private static HttpMessage syntaxMessage() {
        return req("G;id; /$(id);id HTTP/1.1\r\nX-A: $(id)`id` | id && id\r\n\r\n';id;'\"$(id)\"");
    }

    private static HttpMessage nulMessage() {
        return req("POST /x HTTP/1.1\r\nHost: t.example\r\n\r\na\u0000b%s\\");
    }

    public void run() {
        Harness.suite("copy as curl command");

        Harness.test("quote keeps printable text in single quotes and escapes the rest", () -> {
            Expect.equal(CurlCommand.quote(""), "''", "empty");
            Expect.equal(CurlCommand.quote("ab"), "'ab'", "plain");
            Expect.equal(CurlCommand.quote("a'b"), "'a'\\''b'", "quote");
            Expect.equal(CurlCommand.quote("\u0001"), "$'\\x01'", "control");
            Expect.equal(CurlCommand.quote("a\tb\u00ff"), "'a'$'\\x09''b'$'\\xff'", "mixed");
        });

        Harness.test("a body with a backslash before a quote reaches curl byte for byte", () -> {
            String cmd = CurlCommand.of(SVC, req("POST /x HTTP/1.1\r\nHost: t.example\r\n\r\na\\'b"));
            Expect.equal(after(args("bash", cmd), "--data-raw"), "a\\'b", "body");
        });

        Harness.test("a quote in the path reaches curl inside the URL", () -> {
            String cmd = CurlCommand.of(SVC, req("GET /a'b HTTP/1.1\r\nHost: t.example\r\n\r\n"));
            Expect.equal(last(args("bash", cmd)), "https://t.example/a'b", "url");
        });

        Harness.test("shell syntax in the method, a header, the body and the URL stays inert", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC, syntaxMessage()));
            Expect.equal(after(a, "-X"), "G;id;", "method");
            Expect.equal(after(a, "-H"), "X-A: $(id)`id` | id && id", "header");
            Expect.equal(after(a, "--data-raw"), "';id;'\"$(id)\"", "body");
            Expect.equal(last(a), "https://t.example/$(id);id", "url");
        });

        Harness.test("control and high bytes in a header and the body reach curl unchanged", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC, controlMessage()));
            Expect.equal(after(a, "-H"), "X-B: a\tb\u00e9\\x41", "header");
            Expect.equal(after(a, "--data-raw"), allBytes(), "body");
        });

        Harness.test("the command is one line of printable ASCII", () -> {
            String cmd = CurlCommand.of(SVC, controlMessage());
            for (int i = 0; i < cmd.length(); i++) {
                char c = cmd.charAt(i);
                Expect.isTrue(c >= 0x20 && c <= 0x7e, "char " + (int) c + " at " + i);
            }
        });

        Harness.test("a body with a NUL byte goes through printf and reaches curl unchanged", () -> {
            String cmd = CurlCommand.of(SVC, nulMessage());
            Expect.isTrue(cmd.startsWith("printf 'a\\000b%%s\\\\' | curl "), "starts with printf: " + cmd);
            List<String> a = args("bash", cmd);
            Expect.equal(after(a, "--data-binary"), "@-", "reads stdin");
            Expect.isFalse(a.contains("--data-raw"), "no --data-raw");
            // the piped bytes hold a NUL, so the split cut them into pieces
            Expect.equal(String.join("\0", a.subList(a.indexOf("STDIN") + 1, a.size())),
                    "a\u0000b%s\\", "piped bytes");
        });

        Harness.test("a body starting with @ is sent as data, not read from a file", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC,
                    req("POST /x HTTP/1.1\r\nHost: t.example\r\n\r\n@/etc/passwd")));
            Expect.equal(after(a, "--data-raw"), "@/etc/passwd", "body");
            Expect.isFalse(a.contains("--data-binary"), "no --data-binary");
        });

        Harness.test("Content-Length, header lines without a name and lines starting with @ are left out", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC, req("POST /x HTTP/1.1\r\nHost: t.example\r\n"
                    + "Content-Length: 3\r\n@/etc/passwd: x\r\n: odd\r\njunk\r\nX-Z: 1\r\n\r\nabc")));
            Expect.equal(headerValues(a), List.of("Host: t.example", "X-Z: 1"), "headers");
        });

        Harness.test("a header that only starts with @ after trimming is left out too", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC, req("GET / HTTP/1.1\r\n @/etc/passwd:\r\n"
                    + "\t@/etc/passwd:   \r\nX-Z: 1\r\n\r\n")));
            Expect.equal(headerValues(a), List.of("X-Z: 1"), "headers");
        });

        Harness.test("a header with an empty value is sent as Name; so that curl keeps it", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC,
                    req("GET / HTTP/1.1\r\nX-E:\r\nX-F:   \r\n\r\n")));
            Expect.equal(headerValues(a), List.of("X-E;", "X-F;"), "headers");
        });

        Harness.test("a request without a body passes no data option", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC, req("GET / HTTP/1.1\r\nHost: t.example\r\n\r\n")));
            Expect.isFalse(a.contains("--data-raw"), "no --data-raw");
            Expect.isFalse(a.contains("--data-binary"), "no --data-binary");
        });

        Harness.test("HEAD uses --head instead of -X", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC, req("HEAD / HTTP/1.1\r\nHost: t.example\r\n\r\n")));
            Expect.isTrue(a.contains("--head"), "--head");
            Expect.isFalse(a.contains("-X"), "no -X");
        });

        Harness.test("globbing is off and the path is passed as is", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC,
                    req("GET /a/../b?x[]=1&y={2} HTTP/1.1\r\nHost: t.example\r\n\r\n")));
            Expect.isTrue(a.contains("-g"), "-g");
            Expect.isTrue(a.contains("--path-as-is"), "--path-as-is");
            Expect.equal(last(a), "https://t.example/a/../b?x[]=1&y={2}", "url");
        });

        Harness.test("an absolute-form target is used as the URL", () -> {
            List<String> a = args("bash", CurlCommand.of(SVC, req("GET http://a.example/p HTTP/1.1\r\n\r\n")));
            Expect.equal(last(a), "http://a.example/p", "absolute");
            Expect.equal(CurlCommand.url(SVC, ""), "https://t.example", "empty target");
            Expect.equal(CurlCommand.url(SVC, "p"), "https://t.example/p", "relative target");
            Expect.equal(CurlCommand.url(null, "/p"), "/p", "no service");
        });

        Harness.test("zsh and bash 3 read the command the same as bash", () -> {
            for (HttpMessage m : List.of(syntaxMessage(), controlMessage(), nulMessage())) {
                String cmd = CurlCommand.of(SVC, m);
                List<String> expected = args("bash", cmd);
                for (String shell : new String[] {"/bin/zsh", "/bin/bash"}) {
                    if (!new File(shell).exists()) continue;
                    Expect.equal(args(shell, cmd), expected, shell);
                }
            }
        });

        Harness.test("a null request gives an empty command", () -> {
            Expect.equal(CurlCommand.of(SVC, null), "", "empty");
        });
    }
}
