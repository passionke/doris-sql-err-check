# Doris SQL Error Check

Java CLI sidecar that vendors Doris Nereids parser grammar, wires **Origin**, optional **read-only catalog bind**, and expands FE errors to subquery / column doorplates.

Does **not** modify online Doris FE.

Author: kejiqing

**用法（含与其他 MCP 拼装）→ [USAGE.md](USAGE.md)**

## Build

```bash
./scripts/build.sh
# 开发：jar + bin/doris-sql-err-check

REGION=china ./scripts/build-dist.sh
# 分发单文件：
#   dist/doris-sql-err-check-darwin-arm64
#   dist/doris-sql-err-check-linux-amd64
```

目标机需要 JDK 17+。详见 [USAGE.md](USAGE.md)。

## Quick start

```bash
# 本机开发
./bin/doris-sql-err-check --json \
  --sql-text "SELECT a FROM t" \
  --error-message "Unknown column 'a' in 'table list'"

# 分发到 Linux amd64 后
./doris-sql-err-check-linux-amd64 --json \
  --sql-text "SELECT a FROM t" \
  --error-message "Unknown column 'a' in 'table list'"
```

Env: `DORIS_JDBC_URL` / `DORIS_USER` / `DORIS_PASSWORD` / `DORIS_SQL_ERR_CHECK_JAR` / `REGION`

## Test fixtures

Real samples from `th.__internal_schema.audit_log`：`src/test/resources/fixtures/th/{parse,analysis,runtime}/`.

See [VENDOR.md](VENDOR.md) for vendored Doris FE slices.
