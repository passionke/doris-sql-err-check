package io.kejiqing.dorissqlerr.cli;

import io.kejiqing.dorissqlerr.catalog.JdbcDorisCatalog;
import io.kejiqing.dorissqlerr.diagnose.Diagnoser;
import io.kejiqing.dorissqlerr.diagnose.DiagnosisReport;
import io.kejiqing.dorissqlerr.diagnose.ReportJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Java CLI entry.
 * Author: kejiqing
 *
 * Usage:
 *   java -jar doris-sql-err-check.jar --sql q.sql --error err.txt
 *   java -jar doris-sql-err-check.jar --sql-text "SELECT ..." --error-message "..."
 *   java -jar doris-sql-err-check.jar --sql q.sql --jdbc-url jdbc:mysql://fe:9030/db --user u --password p
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a.help || (a.sql == null && a.sqlText == null)) {
            printHelp();
            System.exit(a.help ? 0 : 2);
        }
        String sql = a.sqlText != null ? a.sqlText : read(a.sql);
        String err = a.errorMessage != null ? a.errorMessage
                : (a.error != null ? read(a.error) : "");

        Diagnoser.Options opts = Diagnoser.Options.none();
        JdbcDorisCatalog jdbc = null;
        try {
            if (a.jdbcUrl != null && !a.jdbcUrl.isBlank()) {
                jdbc = new JdbcDorisCatalog(a.jdbcUrl, a.user, a.password);
                opts = Diagnoser.Options.withCatalog(jdbc, a.database);
            } else if (System.getenv("DORIS_JDBC_URL") != null
                    && !System.getenv("DORIS_JDBC_URL").isBlank()) {
                jdbc = JdbcDorisCatalog.fromEnv();
                opts = Diagnoser.Options.withCatalog(jdbc,
                        a.database != null ? a.database : "");
            }

            DiagnosisReport report = new Diagnoser().diagnose(sql, err, opts);
            if (a.json) {
                System.out.println(ReportJson.toJson(report));
            } else {
                System.out.print(report.toHumanText());
            }
        } finally {
            if (jdbc != null) {
                jdbc.close();
            }
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void printHelp() {
        System.out.println("""
                doris-sql-err-check — Doris SQL error diagnose sidecar (Origin + optional catalog bind)
                Author: kejiqing

                Options:
                  --sql <file>              SQL file
                  --sql-text <text>         SQL text
                  --error <file>            FE error message file
                  --error-message <text>    FE error message text
                  --jdbc-url <url>          Read-only catalog via MySQL protocol (DESC)
                  --user <user>             JDBC user (default root / DORIS_USER)
                  --password <pass>         JDBC password (DORIS_PASSWORD)
                  --database <db>           Default database for unqualified tables
                  --json                    Print JSON report
                  --help                    Show help

                Env fallback: DORIS_JDBC_URL, DORIS_USER, DORIS_PASSWORD
                Without JDBC: FE-scope Origin fallback only (no schema truth).
                """);
    }

    static final class Args {
        Path sql;
        Path error;
        String sqlText;
        String errorMessage;
        String jdbcUrl;
        String user = firstEnv("DORIS_USER", "JDBC_USER", "root");
        String password = firstEnv("DORIS_PASSWORD", "JDBC_PASSWORD", "");
        String database = "";
        boolean json;
        boolean help;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> a.help = true;
                    case "--json" -> a.json = true;
                    case "--sql" -> a.sql = Path.of(require(args, ++i, "--sql"));
                    case "--error" -> a.error = Path.of(require(args, ++i, "--error"));
                    case "--sql-text" -> a.sqlText = require(args, ++i, "--sql-text");
                    case "--error-message" -> a.errorMessage = require(args, ++i, "--error-message");
                    case "--jdbc-url" -> a.jdbcUrl = require(args, ++i, "--jdbc-url");
                    case "--user" -> a.user = require(args, ++i, "--user");
                    case "--password" -> a.password = require(args, ++i, "--password");
                    case "--database" -> a.database = require(args, ++i, "--database");
                    default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
                }
            }
            return a;
        }

        private static String require(String[] args, int i, String flag) {
            if (i >= args.length) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return args[i];
        }

        private static String firstEnv(String a, String b, String def) {
            String v = System.getenv(a);
            if (v != null && !v.isBlank()) {
                return v;
            }
            v = System.getenv(b);
            if (v != null && !v.isBlank()) {
                return v;
            }
            return def;
        }
    }
}
