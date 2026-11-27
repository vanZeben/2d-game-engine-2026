package ge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/*
 * Episode 16 check: "Multiplayer Support: TCP vs UDP?"
 * SPEC: the game uses UDP; docs/net.md (< 300 words) says why, and what UDP does not give you.
 *
 * CONTRACT for docs/net.md (checked mechanically; prose quality is the reviewer's job):
 *  - WORDS: whitespace-separated tokens of the whole file, ignoring tokens made only of '#' characters
 *    (Markdown heading markers). Must be 80..299 inclusive.
 *  - STRUCTURE: Markdown headings (lines starting with 1-6 '#') whose text, ignoring case, '*' and ':' and
 *    surrounding whitespace, is exactly one of:  "Why UDP", "Delivery", "Ordering", "Duplicates",
 *    "Connection state".  Each of the five must appear exactly once, and the text under each heading (up to the
 *    next heading) must have at least 8 words.
 *  - The "Why UDP" section must mention "UDP" and "TCP". Each of the four gap sections must contain a
 *    sentence-ish statement of handling: at least one of the words handle/handled/handles/we/game/server/client
 *    (case-insensitive), i.e. it says what the game does, not only what UDP lacks.
 *  - CODE: no source under src/main/java may reference java.net.Socket, ServerSocket, SocketChannel or
 *    ServerSocketChannel (TCP), and some source under src/main/java/ge/net must use DatagramSocket or
 *    DatagramChannel.
 */
public class Ep16Test {
    static final Path ROOT = Paths.get(System.getProperty("user.dir"));
    static final List<String> TOPICS = List.of("Why UDP", "Delivery", "Ordering", "Duplicates", "Connection state");

    static String doc() throws IOException {
        Path p = ROOT.resolve("docs/net.md");
        assertTrue(Files.isRegularFile(p), "docs/net.md must exist");
        return Files.readString(p);
    }

    static int words(String s) {
        int n = 0;
        for (String t : s.trim().split("\\s+")) if (!t.isEmpty() && !t.matches("#+")) n++;
        return n;
    }

    /** heading text (normalised) -> list of bodies (one per occurrence). */
    static Map<String, List<String>> sections(String md) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        String cur = null;
        StringBuilder body = new StringBuilder();
        Pattern h = Pattern.compile("^#{1,6}\\s+(.*)$");
        for (String line : md.split("\\R")) {
            Matcher mt = h.matcher(line);
            if (mt.matches()) {
                if (cur != null) m.computeIfAbsent(cur, k -> new ArrayList<>()).add(body.toString());
                cur = mt.group(1).replace("*", "").replace(":", "").trim().toLowerCase();
                body = new StringBuilder();
            } else body.append(line).append('\n');
        }
        if (cur != null) m.computeIfAbsent(cur, k -> new ArrayList<>()).add(body.toString());
        return m;
    }

    @Test
    void docExistsAndWordCountInRange() throws IOException {
        int n = words(doc());
        assertTrue(n >= 80, "docs/net.md too short: " + n + " words");
        assertTrue(n < 300, "docs/net.md must be under 300 words, has " + n);
    }

    @Test
    void hasEachTopicExactlyOnceWithSubstance() throws IOException {
        Map<String, List<String>> s = sections(doc());
        for (String t : TOPICS) {
            List<String> bodies = s.get(t.toLowerCase());
            assertNotNull(bodies, "missing heading: " + t);
            assertEquals(1, bodies.size(), "heading must appear exactly once: " + t);
            assertTrue(words(bodies.get(0)) >= 8, "section too thin (need >= 8 words): " + t);
        }
    }

    @Test
    void whyUdpMentionsBothProtocols() throws IOException {
        String b = sections(doc()).getOrDefault("why udp", List.of("")).get(0);
        assertTrue(b.contains("UDP"), "Why UDP section must mention UDP");
        assertTrue(b.contains("TCP"), "Why UDP section must mention TCP");
    }

    @Test
    void gapSectionsSayHowTheGameHandlesThem() throws IOException {
        Map<String, List<String>> s = sections(doc());
        Pattern handling = Pattern.compile("\\b(handle[sd]?|we|game|server|client)\\b", Pattern.CASE_INSENSITIVE);
        for (String t : List.of("Delivery", "Ordering", "Duplicates", "Connection state")) {
            String b = s.getOrDefault(t.toLowerCase(), List.of("")).get(0);
            assertTrue(handling.matcher(b).find(), t + " section must say how the game handles it");
        }
    }

    @Test
    void codeUsesUdpNotTcp() throws IOException {
        Path src = ROOT.resolve("src/main/java");
        List<Path> files;
        try (Stream<Path> w = Files.walk(src)) {
            files = w.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        Pattern tcpStrict = Pattern.compile("java\\.net\\.(Server)?Socket\\b|\\b(Server)?SocketChannel\\b|\\bServerSocket\\b|\\bnew\\s+Socket\\s*\\(");
        boolean udp = false;
        for (Path f : files) {
            String code = Files.readString(f);
            Matcher m = tcpStrict.matcher(code);
            if (m.find()) fail("TCP API '" + m.group() + "' used in " + src.relativize(f));
            if (src.relativize(f).toString().replace('\\', '/').startsWith("ge/net/")
                    && (code.contains("DatagramSocket") || code.contains("DatagramChannel"))) udp = true;
        }
        assertTrue(udp, "ge.net must use DatagramSocket or DatagramChannel");
    }
}
