package io.kejiqing.dorissqlerr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.kejiqing.dorissqlerr.diagnose.Diagnoser;
import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;
import io.kejiqing.dorissqlerr.diagnose.ErrorCategory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression on th audit fixtures.
 * Author: kejiqing
 */
public class ThFixtureCorpusTest {
    private final Diagnoser diagnoser = new Diagnoser();
    private final Gson gson = new Gson();

    @Test
    void parseFixturesProduceLocationOrEnhanced() throws Exception {
        List<JsonObject> fixtures = loadAll("fixtures/th/parse");
        assertFalse(fixtures.isEmpty(), "parse fixtures missing");
        int ok = 0;
        for (JsonObject f : fixtures) {
            String sql = f.get("stmt").getAsString();
            String err = f.has("error_message") && !f.get("error_message").isJsonNull()
                    ? f.get("error_message").getAsString() : "";
            DiagnosisReport r = diagnoser.diagnose(sql, err);
            boolean useful = r.category == ErrorCategory.PARSE
                    || (r.location != null && r.location.line != null)
                    || (r.enhancedMessage != null && !r.enhancedMessage.isBlank())
                    || (r.evidence.sidecarParseError != null && !r.evidence.sidecarParseError.isBlank());
            if (useful) {
                ok++;
            }
        }
        assertTrue(ok * 1.0 / fixtures.size() >= 0.7,
                "parse fixture usefulness too low: " + ok + "/" + fixtures.size());
    }

    @Test
    void analysisFixturesFindColumnCandidates() throws Exception {
        List<JsonObject> fixtures = loadAll("fixtures/th/analysis");
        assertFalse(fixtures.isEmpty());
        int withCandidates = 0;
        int withFailed = 0;
        for (JsonObject f : fixtures) {
            DiagnosisReport r = diagnoser.diagnose(f.get("stmt").getAsString(),
                    f.get("error_message").getAsString());
            assertEquals(ErrorCategory.ANALYSIS, r.category);
            if (!r.identifierHits.isEmpty() || !r.identifierCandidates.isEmpty()) {
                withCandidates++;
            }
            if (r.failedHit.isPresent()) {
                withFailed++;
                assertTrue(r.failedHit.get().failed);
                assertNotNull(r.failedHit.get().line);
            }
        }
        assertTrue(withCandidates * 1.0 / fixtures.size() >= 0.5,
                "analysis Origin-hit rate too low: " + withCandidates + "/" + fixtures.size());
        assertTrue(withFailed * 1.0 / fixtures.size() >= 0.5,
                "failed-column resolve rate too low: " + withFailed + "/" + fixtures.size());
    }

    @Test
    void runtimeFixturesDoNotStructureExpand() throws Exception {
        List<JsonObject> fixtures = loadAll("fixtures/th/runtime");
        assertFalse(fixtures.isEmpty());
        for (JsonObject f : fixtures) {
            DiagnosisReport r = diagnoser.diagnose(f.get("stmt").getAsString(),
                    f.get("error_message").getAsString());
            assertEquals(ErrorCategory.RUNTIME, r.category);
            assertEquals("high", r.confidence);
        }
    }

    private List<JsonObject> loadAll(String resourceDir) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        // load from filesystem relative to test classpath root
        Path dir = Path.of("src/test/resources", resourceDir);
        if (!Files.isDirectory(dir)) {
            // fallback: try classloader listing via known files not available — fail clearly
            fail("fixture dir not found: " + dir.toAbsolutePath());
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
                try (InputStream in = Files.newInputStream(p);
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    out.add(gson.fromJson(reader, JsonObject.class));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return out;
    }
}
