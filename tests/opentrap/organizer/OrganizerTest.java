package opentrap.organizer;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the Organizer store and that its items survive the object-stream round
 * trip the project save/restore relies on.
 */
public final class OrganizerTest {

    @SuppressWarnings("unchecked")
    public void run() {
        Harness.suite("organizer");

        Harness.test("items can be added, restored and cleared", () -> {
            OrganizerStore store = new OrganizerStore();
            OrganizerItem it = new OrganizerItem(new HttpService("x.example", 443, true),
                    new HttpMessage(Bytes.bytes("GET /a HTTP/1.1\r\nHost: x.example\r\n\r\n")), null, "login request", "proxy");
            it.status = "in progress";
            it.notes = "check the token";
            store.add(it);
            Expect.equal(store.all().size(), 1, "one item");
            store.clear();
            Expect.equal(store.all().size(), 0, "cleared");
            store.restore(List.of(it));
            Expect.equal(store.all().size(), 1, "restored");
        });

        Harness.test("an in-scope snapshot keeps only the organizer items aimed at the scope", () -> {
            HttpService in = new HttpService("a.test", 443, true);
            HttpService out = new HttpService("b.test", 443, true);
            HttpMessage req = new HttpMessage(Bytes.bytes("GET /x HTTP/1.1\r\nHost: a.test\r\n\r\n"));
            OrganizerStore store = new OrganizerStore();
            OrganizerItem kept = new OrganizerItem(in, req, null, "in", "proxy");
            store.add(kept);
            store.add(new OrganizerItem(out, req, null, "out", "proxy"));
            store.add(new OrganizerItem(null, req, null, "unknown", "proxy"));
            opentrap.core.Scope scope = new opentrap.core.Scope();
            scope.includeUrl(in, "/", true);
            ArrayList<OrganizerItem> snap = store.snapshot(opentrap.core.SaveFilter.inScope(scope));
            Expect.equal(snap.size(), 1, "one item");
            Expect.isTrue(snap.get(0) == kept, "the in-scope one");
            Expect.equal(store.snapshot(opentrap.core.SaveFilter.ALL).size(), 3, "unfiltered keeps all");
        });

        Harness.test("items survive serialization with their status, notes and messages", () -> {
            OrganizerItem it = new OrganizerItem(new HttpService("app.example", 80, false),
                    new HttpMessage(Bytes.bytes("POST /x HTTP/1.1\r\nHost: app.example\r\n\r\nbody")),
                    new HttpMessage(Bytes.bytes("HTTP/1.1 200 OK\r\n\r\nok")), "cap", "repeater");
            it.status = "done";
            it.notes = "verified";
            ArrayList<OrganizerItem> list = new ArrayList<>(List.of(it));

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) { oos.writeObject(list); }
            catch (Exception e) { throw new RuntimeException(e); }
            List<OrganizerItem> back;
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
                back = (List<OrganizerItem>) ois.readObject();
            } catch (Exception e) { throw new RuntimeException(e); }

            Expect.equal(back.size(), 1, "one item back");
            OrganizerItem r = back.get(0);
            Expect.equal(r.status, "done", "status survived");
            Expect.equal(r.notes, "verified", "notes survived");
            Expect.equal(r.host(), "app.example", "service survived");
            Expect.isTrue(r.request != null && r.request.method().equals("POST"), "request survived");
            Expect.isTrue(r.response != null && r.response.statusCode() == 200, "response survived");
        });
    }
}
