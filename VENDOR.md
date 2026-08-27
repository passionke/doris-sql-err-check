# Vendored Doris FE parser slice

Author: kejiqing

This project vendors a **minimal** Apache Doris Nereids parser slice so the sidecar
shares Doris dialect without embedding FE/Planner/Env.

## Copied / derived from Apache Doris (ASF 2.0)

| Artifact | Source |
|----------|--------|
| `DorisLexer.g4` / `DorisParser.g4` | `fe/fe-core/src/main/antlr4/org/apache/doris/nereids/` |
| `Origin`, `CaseInsensitiveStream` | `fe/fe-core/.../nereids/parser/` |
| `ParseException` message shape | `fe/fe-core/.../nereids/exceptions/ParseException.java` |
| `ParseErrorListener` | same package; **patched** to always attach SQL for `^^^` |
| `ParserUtils.withOrigin` | same package; **patched** to push ThreadLocal Origin (FE is no-op) |
| `DorisSqlParser.toAst` | slimmed from `NereidsParser.toAst` (no LogicalPlanBuilder) |
| `SqlOriginIndex` (sidecar) | equivalent of FE `Slot.indexInSqlString` + qualifier (`T0.col`) |
| `FailingColumnResolver` (sidecar) | pick failing occurrence from FE `Unknown column 'x' in 'scope'` (no-catalog fallback) |
| `Catalog` / `JdbcDorisCatalog` / `CatalogBinder` | read-only `DESC` + local bind; authoritative when JDBC provided |

## Not vendored (on purpose)

- `LogicalPlanBuilder` / analyzer / Env — bind is sidecar-local against `DESC`, not FE Env
- Full FE jar as runtime service
- Mutating DDL via catalog client (DESC only)

Aligned grammar version: local tree `/Users/sm4645/work/doris` branch/tag ~ `2.1.10`, ANTLR `4.9.3`.
