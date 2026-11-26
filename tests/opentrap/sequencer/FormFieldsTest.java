package opentrap.sequencer;

import opentrap.core.Bytes;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.List;

/** Tests the input-field scanner behind the Sequencer's form-field choice. */
public final class FormFieldsTest {

    public void run() {
        Harness.suite("sequencer form fields");

        Harness.test("an input lists its name and value wherever the value stands", () -> {
            Expect.equal(FormFields.of("<input type=\"hidden\" name=\"csrf\" value=\"abc\">"
                    + "<INPUT VALUE='y' NAME='x'><input name=q value=1 >"),
                    List.of("csrf=abc", "x=y", "q=1"), "fields");
        });

        Harness.test("only input tags count and a field without a name is left out", () -> {
            Expect.equal(FormFields.of("<inputx name=a><textarea name=t></textarea>"
                    + "<input value=b><input name=\"\"><input/name=d>"),
                    List.of("d="), "fields");
        });

        Harness.test("the first name and value of a tag win and a repeated field is listed once", () -> {
            Expect.equal(FormFields.of("<input name=a name=b value=1 value=2><input name=a value=1>"),
                    List.of("a=1"), "fields");
        });

        Harness.test("a quoted value may hold angle brackets and an unclosed quote runs to the end", () -> {
            // The swallowed v is the accepted browser-like behaviour: the open quote takes the rest.
            Expect.equal(FormFields.of("<input name=t value=\"a>b<c\"><input name=u value=\"x><input name=v>"),
                    List.of("t=a>b<c", "u=x><input name=v>"), "fields");
        });

        Harness.test("a megabyte of unclosed input tags is scanned in linear time", () -> {
            for (String unit : new String[] {"<input ", "<input name=\"", "<input name=a ", "<input value='"}) {
                String s = unit.repeat(1_000_000 / unit.length());
                long t0 = System.nanoTime();
                List<String> got = FormFields.of(s);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Expect.isTrue(ms < 250, unit + " took " + ms + " ms");
                Expect.isTrue(got.size() <= 1, unit + " gave " + got.size() + " entries");
                if (unit.equals("<input ")) Expect.isTrue(got.isEmpty(), "no name, no entry");
            }
        });

        Harness.test("at most 1000 fields are listed and long names and values are cut", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) sb.append("<input name=f").append(i).append('>');
            List<String> got = FormFields.of(sb.toString());
            Expect.equal(got.size(), 1000, "count");
            Expect.equal(got.get(0), "f0=", "first");
            Expect.equal(got.get(999), "f999=", "last");

            Expect.equal(FormFields.of("<input name=" + "n".repeat(300) + " value=" + "v".repeat(300) + ">"),
                    List.of("n".repeat(256) + "...=" + "v".repeat(256) + "..."), "cut");
            Expect.equal(FormFields.of("<input name=k value=" + "v".repeat(256) + ">"),
                    List.of("k=" + "v".repeat(256)), "exactly at the limit");
        });

        Harness.test("a body is scanned up to the scan limit only", () -> {
            byte[] body = Bytes.bytes(" ".repeat(FormFields.SCAN_LIMIT - 20) + "<input name=a><input name=b>");
            Expect.equal(FormFields.of(body), List.of("a="), "fields");
        });
    }
}
