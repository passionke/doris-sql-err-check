# USAGE — doris-sql-err-check

Author: kejiqing

给 **Agent / 其他 MCP** 用的调用约定。本工具是 **sidecar CLI**（不是 Doris MCP 本身）：吃失败 SQL +（可选）FE 报错，吐结构化诊断 JSON。

---

## 1. 角色边界（和别的 MCP 怎么拼）

| 组件 | 负责 | 不负责 |
|------|------|--------|
| **Doris MCP**（如 `user-doris-kejiqing`） | 跑 SQL、`DESC`/`SHOW`、捞 `audit_log` | 把门牌号钉到子查询/列引用 |
| **SQLBoy / sqlbot-admin MCP** | 数据源、字段注解、示例、对话记录 | 解析 Origin / catalog bind |
| **本工具 CLI** | 语法 Origin、未知列 `failedHit`、可选只读 catalog bind | 改线上 FE；不当查询引擎 |

推荐流水线：

```text
其他 MCP 取得失败样本
  ├─ stmt / sql
  └─ error_message（可空）
        │
        ▼
doris-sql-err-check  --json
        │
        ▼
读 category / failedHit / availableColumns
        │
        ├─ PARSE  → 按 location + enhanced 改 SQL
        ├─ ANALYSIS → 按 failedHit + availableColumns 改列名/投影
        └─ RUNTIME → 别当 SQL 结构问题修
```

---

## 2. 构建产物与 CLI

### 开发用（本仓）

```bash
./scripts/build.sh
# → target/doris-sql-err-check-0.1.0-SNAPSHOT.jar
# → bin/doris-sql-err-check   （bash 启动器）
```

### 分发用单文件（两个平台，一次打出）

```bash
REGION=china ./scripts/build-dist.sh
# → dist/doris-sql-err-check-darwin-arm64   # macOS Apple Silicon
# → dist/doris-sql-err-check-linux-amd64    # Linux x86_64
# → dist/SHA256SUMS
```

| 文件 | 目标机 |
|------|--------|
| `doris-sql-err-check-darwin-arm64` | macOS arm64 |
| `doris-sql-err-check-linux-amd64` | Linux amd64 |

每个是 **一个可执行文件**（Go launcher 内嵌 fat jar）。目标机仍需 **JDK 17+**（`JAVA_HOME` 或 `PATH` 里有 `java`）。

```bash
# 拷到 Linux amd64 后：
chmod +x doris-sql-err-check-linux-amd64
./doris-sql-err-check-linux-amd64 --json \
  --sql-text "$SQL" \
  --error-message "$ERR"
```

`REGION=china`（默认）走阿里云 Go 镜像；海外用 `REGION=other`。

> 无 JVM 的真 native（GraalVM）另议；当前单文件策略是 **跨平台可拷贝 CLI + 目标机 JDK**。

需要 JDK 时也可用：

```bash
./bin/doris-sql-err-check --json --sql-text "$SQL" --error-message "$ERR"
# 或
java -jar target/doris-sql-err-check-0.1.0-SNAPSHOT.jar --json ...
```

---

## 3. CLI 契约

### 输入

| 参数 | 必填 | 说明 |
|------|------|------|
| `--sql <file>` 或 `--sql-text <text>` | **是** | 失败 SQL |
| `--error <file>` 或 `--error-message <text>` | 否 | FE `error_message`；空则仍可 parse / catalog bind |
| `--jdbc-url` / `--user` / `--password` / `--database` | 否 | 只读 catalog（`DESC`）；有则 **catalog-bind 优先** |
| `--json` | 建议 MCP 必开 | stdout 只打 JSON，便于解析 |

环境变量兜底：`DORIS_JDBC_URL`、`DORIS_USER`、`DORIS_PASSWORD`。

### 退出码

| code | 含义 |
|------|------|
| `0` | 诊断完成（不论 category） |
| `2` | 缺 `--sql` / `--sql-text` 或 `--help` |
| 非 0 | 启动/参数异常 |

> 诊断「有错」不走非零退出；由 JSON 里的 `category` / `failedHit` 表达。

### 最小调用（无 catalog，FE-scope）

```bash
./bin/doris-sql-err-check --json \
  --sql-text "$SQL" \
  --error-message "$ERR"
```

### 推荐调用（有 catalog，未知列以 schema 为准）

```bash
./bin/doris-sql-err-check --json \
  --sql-text "$SQL" \
  --error-message "$ERR" \
  --jdbc-url "jdbc:mysql://${FE_HOST}:9030/${DB}" \
  --user "$DORIS_USER" \
  --password "$DORIS_PASSWORD" \
  --database "$DB"
```

### 文件输入（长 SQL）

```bash
printf '%s' "$SQL" > /tmp/q.sql
printf '%s' "$ERR" > /tmp/e.txt
./bin/doris-sql-err-check --json --sql /tmp/q.sql --error /tmp/e.txt --database "$DB"
```

---

## 4. JSON 输出（MCP 消费面）

`--json` 输出 **一个** JSON object（UTF-8）。Agent 应优先读这些字段：

### 顶层

| 字段 | 类型 | 含义 |
|------|------|------|
| `category` | string | `PARSE` \| `ANALYSIS` \| `RUNTIME` \| `UNKNOWN` |
| `confidence` | string | `high` \| `medium` \| `low` |
| `enhancedMessage` | string | 给人看的增强说明 |
| `failedHit` | object\|null | **失败列引用**（ANALYSIS 核心） |
| `location` | object | line / column / charOffset / snippet / caret |
| `structure` | object | clause / enclosingSubquery / nearestFunction… |
| `identifierHits` | array | 同名列所有 Origin 命中 |
| `evidence` | object | bind 模式与 catalog 证据 |

### `failedHit`（有则优先用）

```json
{
  "name": "settle_time",
  "qualifier": "T0",
  "qualifiedName": "T0.settle_time",
  "line": 9,
  "column": 16,
  "clause": "WHERE",
  "failed": true,
  "snippet": "...",
  "caret": "..."
}
```

### `evidence`

| 字段 | 含义 |
|------|------|
| `bindMode` | `catalog-bind` \| `fe-scope` \| `none` |
| `availableColumns` | 作用域内可见列（catalog 时） |
| `loadedRelations` | 已加载的表/CTE/子查询 |
| `missingBaseTables` | DESC 失败的基表 |
| `rawError` | 原始 FE 文案 |
| `sidecarParseError` | sidecar 解析异常原文 |
| `matchedPattern` | 命中路径标签（调试用） |

### Agent 决策伪代码

```text
report = JSON.parse(stdout)
switch report.category:
  RUNTIME  → 不改 SQL 结构；提示超时/资源
  PARSE    → 用 report.location + enhancedMessage 修语法
  ANALYSIS →
      if report.failedHit:
          修 report.failedHit.qualifiedName @ line/column
          若 evidence.availableColumns 非空：从中选替代列
      else:
          退回 identifierHits / enhancedMessage
```

---

## 5. 和其他 MCP 的拼法（可直接当 skill 步骤）

### A. Doris MCP 捞失败样本 → 本工具诊断

1. `doris_query`：从 `__internal_schema.audit_log` 取 `stmt` + `error_message`（或业务侧失败日志）
2. 调本 CLI `--json`（有 FE 账号则带 JDBC）
3. 用 `failedHit` / `availableColumns` 生成修复建议
4. （可选）`doris_query` 再跑修复后 SQL 验证

### B. Doris MCP 只当 catalog，本工具做 bind

若不想给 CLI 配 JDBC，也可：

1. 本工具 **无 JDBC** 先出 `failedHit`（FE-scope）或 parse 位置
2. 对涉及表用 Doris MCP：`doris_table_information` / `DESC`
3. Agent 自己对照 `availableColumns`——但 **不如直接给 CLI `--jdbc-url`**（一次契约、少分叉）

### C. SQLBoy / 对话 MCP

1. 从 chat / example 取出失败 SQL + 报错
2. 本 CLI `--json`
3. 把 `enhancedMessage` + `failedHit` 写回用户可读回复；需要改库表注释时再调 admin MCP

---

## 6. Shell 封装示例（给 Agent 调）

```bash
#!/usr/bin/env bash
# diagnose.sh — stdin: JSON {"sql":"...","error":"...","database":"..."}
# stdout: diagnosis JSON
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/target/doris-sql-err-check-0.1.0-SNAPSHOT.jar"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

IN="$(cat)"
SQL=$(printf '%s' "$IN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sql"])')
ERR=$(printf '%s' "$IN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("error",""))')
DB=$(printf '%s' "$IN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("database",""))')

ARGS=("$ROOT/bin/doris-sql-err-check" --json --sql-text "$SQL" --error-message "$ERR")
if [[ -n "${DORIS_JDBC_URL:-}" ]]; then
  ARGS+=(--database "$DB")
fi
"${ARGS[@]}"
```

---

## 7. 模式对照（调用方别混）

| `evidence.bindMode` | 何时 | 可信点 |
|---------------------|------|--------|
| `catalog-bind` | 配了 JDBC / `DORIS_JDBC_URL` | **schema 为准**；FE 文案旁证 |
| `fe-scope` | 无 catalog | 信 FE「哪列/哪个 scope」，外挂钉 Origin 门牌 |
| `none` | 未走到分析绑定 | 看 parse / runtime |

---

## 8. 明确不做

- 不提供 MCP server 端口（当前是 **jar CLI**；要 MCP 化需另包一层 stdio/HTTP）
- 不改线上 Doris FE
- 不把 `audit_log` 扫进热路径（语料离线用）

若要把本工具注册成 Cursor MCP，下一步是：stdio wrapper 调上述 CLI，tool 名建议 `doris_sql_diagnose`，入参 `sql` / `error_message` / `database`，出参即本节 JSON。
