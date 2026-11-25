package opentrap.decoder;

import opentrap.core.Bytes;
import opentrap.testkit.Allocation;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Tests the decoder transforms added for wider coverage: gzip/deflate round
 * trips, gzip recognition by smart-decode, and the expanded set of named HTML
 * entities. Assertions use byte values so the source stays ASCII-only.
 */
public final class DecoderTransformsTest {

    private static byte[] dec(String name, String in) {
        try { return Transforms.decode(name, Bytes.bytes(in)); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] dec(String name, byte[] in) {
        try { return Transforms.decode(name, in); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] enc(String name, byte[] in) {
        try { return Transforms.encode(name, in); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] zeros(int n) { return new byte[n]; }

    private static byte[] repeat(byte[] member, int times) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(member.length * times);
        for (int i = 0; i < times; i++) out.write(member, 0, member.length);
        return out.toByteArray();
    }

    private static byte[] hex(String s) { return dec("ascii hex", s); }

    private static byte[] rawDeflate(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
            d.write(in);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    /** Decodes with a limit and checks that it was cut at {@code n} zero bytes. */
    private static Transforms.Truncated truncatedAt(String name, byte[] in, int limit) {
        try {
            Transforms.decode(name, in, limit);
        } catch (Transforms.Truncated t) {
            Expect.isTrue(Arrays.equals(zeros(limit), t.partial()), name + " partial is " + limit + " zero bytes");
            return t;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new AssertionError(name + " was not truncated");
    }

    public void run() {
        Harness.suite("decoder transforms");

        Harness.test("gzip and deflate round-trip", () -> {
            byte[] data = Bytes.bytes("hello hello hello world world");
            Expect.isTrue(Arrays.equals(data, dec("gzip", enc("gzip", data))), "gzip");
            Expect.isTrue(Arrays.equals(data, dec("deflate", enc("deflate", data))), "deflate");
        });

        Harness.test("smart-decode recognises gzip by its magic number", () -> {
            byte[] gz = enc("gzip", Bytes.bytes("some compressible text text text"));
            Expect.equal(Transforms.guess(gz), " gzip", "guess gzip");
        });

        Harness.test("expanded named HTML entities decode to their code points", () -> {
            byte[] copy = dec("html", "&copy;");
            Expect.equal(copy.length, 1, "copyright is one Latin-1 byte");
            Expect.equal(copy[0] & 0xff, 169, "copyright code point");
            byte[] guil = dec("html", "&laquo;x&raquo;");
            Expect.equal(guil.length, 3, "two guillemets around x");
            Expect.equal(guil[0] & 0xff, 171, "laquo");
            Expect.equal(guil[2] & 0xff, 187, "raquo");
            Expect.isTrue(dec("html", "&euro;").length > 0, "euro decodes to something");
        });

        Harness.test("brotli encode is reported as unsupported", () -> {
            boolean threw = false;
            try { Transforms.encode("brotli", Bytes.bytes("x")); } catch (Exception e) { threw = true; }
            Expect.isTrue(threw, "brotli encode refused");
        });

        Harness.test("a gzip that expands to 10 GB decodes to its first 16 MB and says so", () -> {
            byte[] bomb = repeat(enc("gzip", zeros(1 << 20)), 10240);
            Transforms.Truncated[] t = new Transforms.Truncated[1];
            long bytes = Allocation.during(() -> {
                try {
                    Transforms.decode("gzip", bomb);
                } catch (Transforms.Truncated e) {
                    t[0] = e;
                }
            });
            Expect.notNull(t[0], "truncated");
            Expect.isTrue(Arrays.equals(zeros(Transforms.MAX_OUTPUT), t[0].partial()), "partial is 16 MB of zeros");
            Expect.equal(t[0].getMessage(), "output truncated at 16 MB", "message");
            Expect.isTrue(bytes < 3L * Transforms.MAX_OUTPUT, "allocated " + bytes);
        });

        Harness.test("a gzip of exactly 16 MB decodes whole", () -> {
            byte[] out = dec("gzip", repeat(enc("gzip", zeros(1 << 20)), 16));
            Expect.isTrue(Arrays.equals(zeros(Transforms.MAX_OUTPUT), out), "16 MB of zeros");
        });

        Harness.test("deflate over the limit is truncated, zlib-wrapped or raw", () -> {
            byte[] big = zeros(Transforms.MAX_OUTPUT + 1);
            truncatedAt("deflate", enc("deflate", big), Transforms.MAX_OUTPUT);
            truncatedAt("deflate", rawDeflate(big), Transforms.MAX_OUTPUT);
        });

        Harness.test("brotli over the limit is truncated and brotli at the limit is whole", () -> {
            truncatedAt("brotli", hex("cfffff7ff82700e2b14020f7fe1f0000010003"), Transforms.MAX_OUTPUT);
            byte[] out = dec("brotli", hex("cfffff7ff82700e2b14020f7fe7f"));
            Expect.isTrue(Arrays.equals(zeros(Transforms.MAX_OUTPUT), out), "16 MB of zeros");
        });

        Harness.test("smart decode recognises a gzip whose output is over the limit", () -> {
            Expect.equal(Transforms.guess(enc("gzip", zeros(4096)), 1024), " gzip", "guess gzip");
        });

        Harness.test("a limit that is not a whole number of MB is named in bytes", () -> {
            Transforms.Truncated t = truncatedAt("gzip", enc("gzip", zeros(4096)), 1024);
            Expect.equal(t.getMessage(), "output truncated at 1024 bytes", "message");
        });
    }
}
