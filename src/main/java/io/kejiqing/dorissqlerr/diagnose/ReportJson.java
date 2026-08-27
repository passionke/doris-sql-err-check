package io.kejiqing.dorissqlerr.diagnose;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Stable JSON export for MCP / agent pipelines (no java.util.Optional reflection).
 * Author: kejiqing
 */
public final class ReportJson {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private ReportJson() {
    }

    public static String toJson(DiagnosisReport r) {
        return GSON.toJson(toObject(r));
    }

    public static JsonObject toObject(DiagnosisReport r) {
        JsonObject o = new JsonObject();
        o.addProperty("category", r.category == null ? "UNKNOWN" : r.category.name());
        o.addProperty("confidence", nullToEmpty(r.confidence));
        o.addProperty("enhancedMessage", nullToEmpty(r.enhancedMessage));
        o.add("location", location(r.location));
        o.add("structure", structure(r.structure));
        o.add("failedHit", candidate(r.failedHit.orElse(null)));
        o.add("identifierHits", candidates(r.identifierHits));
        JsonArray flat = new JsonArray();
        if (r.identifierCandidates != null) {
            for (String s : r.identifierCandidates) {
                flat.add(s);
            }
        }
        o.add("identifierCandidates", flat);
        o.add("evidence", evidence(r.evidence));
        return o;
    }

    private static JsonObject location(DiagnosisReport.Location loc) {
        JsonObject o = new JsonObject();
        if (loc == null) {
            return o;
        }
        if (loc.line != null) {
            o.addProperty("line", loc.line);
        }
        if (loc.column != null) {
            o.addProperty("column", loc.column);
        }
        if (loc.charOffset != null) {
            o.addProperty("charOffset", loc.charOffset);
        }
        o.addProperty("snippet", nullToEmpty(loc.snippet));
        o.addProperty("caret", nullToEmpty(loc.caret));
        return o;
    }

    private static JsonObject structure(DiagnosisReport.Structure s) {
        JsonObject o = new JsonObject();
        if (s == null) {
            return o;
        }
        if (s.enclosingSubquery.isPresent()) {
            DiagnosisReport.Subquery q = s.enclosingSubquery.get();
            JsonObject sq = new JsonObject();
            sq.addProperty("depth", q.depth);
            sq.addProperty("alias", nullToEmpty(q.alias));
            sq.addProperty("sqlSlice", nullToEmpty(q.sqlSlice));
            sq.addProperty("startOffset", q.startOffset);
            sq.addProperty("stopOffset", q.stopOffset);
            o.add("enclosingSubquery", sq);
        } else {
            o.add("enclosingSubquery", JsonNull.INSTANCE);
        }
        o.addProperty("clause", s.clause.orElse(null));
        if (s.nearestFunction.isPresent()) {
            DiagnosisReport.FunctionHit f = s.nearestFunction.get();
            JsonObject fo = new JsonObject();
            fo.addProperty("name", f.name);
            if (f.argIndex != null) {
                fo.addProperty("argIndex", f.argIndex);
            }
            fo.addProperty("startOffset", f.startOffset);
            o.add("nearestFunction", fo);
        } else {
            o.add("nearestFunction", JsonNull.INSTANCE);
        }
        if (s.nearestIdentifier.isPresent()) {
            DiagnosisReport.IdentifierHit i = s.nearestIdentifier.get();
            JsonObject io = new JsonObject();
            io.addProperty("name", i.name);
            io.addProperty("kind", i.kind);
            io.addProperty("startOffset", i.startOffset);
            io.addProperty("stopOffset", i.stopOffset);
            o.add("nearestIdentifier", io);
        } else {
            o.add("nearestIdentifier", JsonNull.INSTANCE);
        }
        return o;
    }

    private static JsonArray candidates(java.util.List<DiagnosisReport.IdentifierCandidate> list) {
        JsonArray arr = new JsonArray();
        if (list == null) {
            return arr;
        }
        for (DiagnosisReport.IdentifierCandidate c : list) {
            arr.add(candidate(c));
        }
        return arr;
    }

    private static com.google.gson.JsonElement candidate(DiagnosisReport.IdentifierCandidate c) {
        if (c == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", nullToEmpty(c.name));
        o.addProperty("qualifier", nullToEmpty(c.qualifier));
        o.addProperty("qualifiedName", c.qualifiedName());
        if (c.line != null) {
            o.addProperty("line", c.line);
        }
        if (c.column != null) {
            o.addProperty("column", c.column);
        }
        o.addProperty("startOffset", c.startOffset);
        o.addProperty("stopOffset", c.stopOffset);
        o.addProperty("clause", nullToEmpty(c.clause));
        if (c.subqueryDepth != null) {
            o.addProperty("subqueryDepth", c.subqueryDepth);
        }
        o.addProperty("subquerySlice", nullToEmpty(c.subquerySlice));
        o.addProperty("snippet", nullToEmpty(c.snippet));
        o.addProperty("caret", nullToEmpty(c.caret));
        o.addProperty("failed", c.failed);
        return o;
    }

    private static JsonObject evidence(DiagnosisReport.Evidence e) {
        JsonObject o = new JsonObject();
        if (e == null) {
            return o;
        }
        o.addProperty("matchedPattern", nullToEmpty(e.matchedPattern));
        o.addProperty("rawError", nullToEmpty(e.rawError));
        o.addProperty("sidecarParseError", nullToEmpty(e.sidecarParseError));
        o.addProperty("bindMode", nullToEmpty(e.bindMode));
        JsonArray avail = new JsonArray();
        if (e.availableColumns != null) {
            for (String s : e.availableColumns) {
                avail.add(s);
            }
        }
        o.add("availableColumns", avail);
        JsonArray loaded = new JsonArray();
        if (e.loadedRelations != null) {
            for (String s : e.loadedRelations) {
                loaded.add(s);
            }
        }
        o.add("loadedRelations", loaded);
        JsonArray missing = new JsonArray();
        if (e.missingBaseTables != null) {
            for (String s : e.missingBaseTables) {
                missing.add(s);
            }
        }
        o.add("missingBaseTables", missing);
        return o;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
