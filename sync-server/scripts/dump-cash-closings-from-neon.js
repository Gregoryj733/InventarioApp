/**
 * Dump cashClosings from Neon (DATABASE_URL) to a local JSON file.
 * Usage: DATABASE_URL=... node scripts/dump-cash-closings-from-neon.js
 */
const { Pool } = require("pg");
const fs = require("fs");
const path = require("path");

function stripSslModeParam(connectionString) {
  try {
    const url = new URL(connectionString);
    url.searchParams.delete("sslmode");
    url.searchParams.delete("channel_binding");
    return url.toString();
  } catch {
    return connectionString;
  }
}

function asArray(value) {
  let current = value;
  for (let i = 0; i < 5 && typeof current === "string"; i += 1) {
    try {
      current = JSON.parse(current);
    } catch {
      return [];
    }
  }
  return Array.isArray(current) ? current : [];
}

async function main() {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) {
    throw new Error("DATABASE_URL no configurada");
  }

  const pool = new Pool({
    connectionString: stripSslModeParam(connectionString),
    ssl: { rejectUnauthorized: false },
    connectionTimeoutMillis: 30_000
  });

  try {
    await pool.query("SELECT 1");
    console.log("neon_ok 1");

    const tables = await pool.query(
      "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
    );
    console.log(
      "tables",
      tables.rows.map((r) => r.table_name).join(",")
    );

    let closings = [];
    let nextId = 1;

    try {
      const r = await pool.query(
        "SELECT key, value FROM sync_kv WHERE key = $1",
        ["cashClosings"]
      );
      if (r.rows[0]) {
        const parsed =
          typeof r.rows[0].value === "string"
            ? JSON.parse(r.rows[0].value)
            : r.rows[0].value;
        closings = asArray(parsed?.items ?? parsed);
        nextId = Number(parsed?.nextId) || closings.length + 1;
        console.log("from_sync_kv", closings.length);
      } else {
        console.log("sync_kv_cashClosings_missing");
      }
    } catch (error) {
      console.log("sync_kv_err", error.message);
    }

    try {
      const r = await pool.query("SELECT cash_closings FROM sync_state LIMIT 1");
      if (r.rows[0]) {
        const arr = asArray(r.rows[0].cash_closings);
        console.log("from_sync_state", arr.length);
        if (!closings.length && arr.length) closings = arr;
      }
    } catch (error) {
      console.log("sync_state_err", error.message);
    }

    const startOfToday = new Date();
    startOfToday.setHours(0, 0, 0, 0);
    const cutoff = startOfToday.getTime();
    const untilYesterday = closings.filter((c) => Number(c.closedAt) < cutoff);

    const statuses = {};
    for (const c of closings) {
      const s = c.status || "UNKNOWN";
      statuses[s] = (statuses[s] || 0) + 1;
    }

    console.log("total", closings.length, "until_yesterday", untilYesterday.length);
    console.log("statuses", JSON.stringify(statuses));
    console.log(
      "sample",
      closings
        .slice(0, 8)
        .map((c) => `${c.dateText}/${c.status}/${c.username}`)
        .join(" | ")
    );

    const outPath = path.join(
      process.env.TEMP || ".",
      "cash-closings-restore.json"
    );
    fs.writeFileSync(
      outPath,
      JSON.stringify(
        {
          dumpedAt: new Date().toISOString(),
          nextCashClosingId: nextId,
          all: closings,
          untilYesterday
        },
        null,
        2
      )
    );
    console.log("wrote", outPath);
  } finally {
    await pool.end();
  }
}

main().catch((error) => {
  console.error("FAIL", error.code || "", error.message);
  process.exit(1);
});
