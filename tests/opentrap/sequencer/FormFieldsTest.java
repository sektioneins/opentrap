package opentrap.sequencer;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JComboBox;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
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

        Harness.test("a response full of unclosed input tags is selected within a second", () -> {
            onEdt(() -> {
                SequencerTool tool = new SequencerTool();
                Container tab = (Container) tool.tabs()[0].component();
                long t0 = System.nanoTime();
                tool.receive(null, request(), response("<input ".repeat(20_000)));
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Expect.isTrue(ms < 1000, "receive took " + ms + " ms");
                JComboBox<?> forms = formList(tab);
                Expect.equal(forms.getItemCount(), 0, "form fields");
                Expect.isFalse(forms.isEnabled(), "list enabled");
                Expect.isFalse(formRadio(tab).isEnabled(), "radio enabled");
            });
        });

        Harness.test("a later request replaces the form fields of the one before", () -> {
            onEdt(() -> {
                SequencerTool tool = new SequencerTool();
                Container tab = (Container) tool.tabs()[0].component();
                tool.receive(null, request(), response("<input ".repeat(20_000)));
                tool.receive(null, request(), response(
                        "<form><input type=\"hidden\" name=\"csrf\" value=\"abc\"><input name=q></form>"));
                JComboBox<?> forms = formList(tab);
                Expect.equal(forms.getItemCount(), 2, "form fields");
                Expect.equal(forms.getItemAt(0), "csrf=abc", "first");
                Expect.equal(forms.getItemAt(1), "q=", "second");
                Expect.isTrue(forms.isEnabled(), "list enabled");
                Expect.isTrue(formRadio(tab).isEnabled(), "radio enabled");
                tool.receive(null, request(), response("<p>none</p>"));
                Expect.equal(forms.getItemCount(), 0, "form fields after");
                Expect.isFalse(forms.isEnabled(), "list enabled after");
                Expect.isFalse(formRadio(tab).isEnabled(), "radio enabled after");
            });
        });
    }

    private static void onEdt(Runnable r) {
        try { SwingUtilities.invokeAndWait(r); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static <T extends Component> List<T> all(Container c, Class<T> type, List<T> out) {
        for (Component child : c.getComponents()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Container k) all(k, type, out);
        }
        return out;
    }

    /** The form list of the live capture tab: the second combo box, after the cookie list. */
    private static JComboBox<?> formList(Container tab) {
        return all(tab, JComboBox.class, new ArrayList<>()).get(1);
    }

    private static JRadioButton formRadio(Container tab) {
        for (JRadioButton b : all(tab, JRadioButton.class, new ArrayList<>())) {
            if ("Form field:".equals(b.getText())) return b;
        }
        throw new AssertionError("no Form field radio button");
    }

    private static HttpMessage request() {
        return new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: t\r\n\r\n"));
    }

    private static HttpMessage response(String body) {
        return new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" + body));
    }
}
