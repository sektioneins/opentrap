package opentrap.report;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.LogEntry;
import opentrap.spider.Cdp.JsonValue;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.List;

/**
 * Tests the exporters: the issue report (HTML/XML) carries and escapes the
 * findings, and the traffic exports produce valid CSV and parseable HAR JSON.
 */
public final class ExportTest {

    private static Issue sample() {
        return Issue.builder("Reflected <XSS>")
                .high().certain()
                .service(new HttpService("app.example.com", 443, true))
                .path("/search").param("q")
                .detail("User input reflected unencoded.")
                .remediation("Encode on output.")
                .evidence("<script>alert(1)</script>")
                .cwe(79)
                .build();
    }

    private static LogEntry entry() {
        HttpService svc = new HttpService("app.example.com", 443, true);
        LogEntry e = new LogEntry("proxy", svc,
                new HttpMessage(Bytes.bytes("GET /x?a=1 HTTP/1.1\r\nHost: app.example.com\r\n\r\n")));
        e.setResponse(new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 2\r\n\r\nhi")));
        e.setDurationMillis(42);
        return e;
    }

    public void run() {
        Harness.suite("export");

        Harness.test("the HTML report includes the finding and escapes markup", () -> {
            String html = Export.issuesHtml(List.of(sample()));
            Expect.isTrue(html.contains("Reflected &lt;XSS&gt;"), "title escaped");
            Expect.isTrue(html.contains("CWE-79"), "cwe present");
            Expect.isTrue(html.contains("Encode on output."), "remediation present");
            Expect.isFalse(html.contains("<script>alert(1)</script>"), "evidence markup not left raw");
        });

        Harness.test("the XML report is well-formed-ish and escapes entities", () -> {
            String xml = Export.issuesXml(List.of(sample()));
            Expect.isTrue(xml.startsWith("<?xml"), "xml prolog");
            Expect.isTrue(xml.contains("<title>Reflected &lt;XSS&gt;</title>"), "escaped title element");
            Expect.isTrue(xml.contains("<cwe>79</cwe>"), "cwe element");
        });

        Harness.test("traffic CSV has a header and one row per entry", () -> {
            String csv = Export.trafficCsv(List.of(entry(), entry()));
            String[] lines = csv.split("\n");
            Expect.isTrue(lines[0].startsWith("number,time,tool,method,url"), "header");
            Expect.equal(lines.length, 3, "header + two rows");
        });

        Harness.test("traffic HAR is valid JSON with an entries array", () -> {
            String har = Export.trafficHar(List.of(entry()));
            JsonValue v = JsonValue.parse(har);     // throws / returns null if malformed
            Expect.notNull(v, "HAR parsed as JSON");
            Expect.isTrue(har.contains("\"entries\""), "has entries");
            Expect.isTrue(har.contains("\"status\":200"), "response status recorded");
        });

        Harness.test("the HTML report's severity badges and heading read on their fills", () -> {
            String html = Export.issuesHtml(List.of(sample()));
            for (String sev : List.of("High", "Medium", "Low", "Information")) {
                var m = java.util.regex.Pattern.compile("\\." + sev + "\\{background:#([0-9A-Fa-f]{3,6});").matcher(html);
                Expect.isTrue(m.find(), sev + " badge colour");
                java.awt.Color fill = css(m.group(1));
                double r = opentrap.ui.theme.Contrast.ratio(java.awt.Color.WHITE, fill);
                Expect.isTrue(r >= 4.5, sev + ": white on #" + m.group(1) + " is " + String.format(java.util.Locale.ROOT, "%.2f", r));
            }
            var h1 = java.util.regex.Pattern.compile("h1\\{color:#([0-9A-Fa-f]{6});").matcher(html);
            Expect.isTrue(h1.find(), "heading colour");
            // Large text would pass at 3 to 1; the fork's accent reaches 4.77, so hold it to 4.5.
            Expect.isTrue(opentrap.ui.theme.Contrast.ratio(css(h1.group(1)), java.awt.Color.WHITE) >= 4.5, "heading on white");
        });
    }

    /** A CSS hex colour, three or six digits. */
    private static java.awt.Color css(String hex) {
        if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        return new java.awt.Color(Integer.parseInt(hex, 16));
    }
}
