/**
 * Herramienta de mantenimiento: actualiza el hash de contraseña de un
 * usuario existente directamente en la fila "users" de sync_kv, sin pasar
 * por la API (útil para el usuario "admin", que se gestiona fuera de la API
 * de usuarios administrables). Pensada para ejecutarse como one-off job en
 * Render, donde ya está disponible DATABASE_URL en el entorno.
 *
 * Uso: node scripts/set-user-password.js <payload-base64>
 * payload-base64 = Buffer.from(JSON.stringify({ username, passwordHash })).toString("base64")
 *
 * No recibe la contraseña en texto plano ni la registra en logs: el hash
 * bcrypt se calcula fuera de este script y solo se referencia aquí como
 * dato opaco.
 */
const { Pool } = require("pg");

function asArray(value) {
  let current = value;
  for (let attempts = 0; attempts < 5 && typeof current === "string"; attempts += 1) {
    try {
      current = JSON.parse(current);
    } catch (error) {
      return [];
    }
  }
  return Array.isArray(current) ? current : [];
}

async function main() {
  const payloadB64 = process.argv[2];
  if (!payloadB64) {
    throw new Error("Falta el payload base64 (username + passwordHash)");
  }
  const { username, passwordHash } = JSON.parse(Buffer.from(payloadB64, "base64").toString("utf8"));
  if (!username || !passwordHash) {
    throw new Error("El payload debe incluir username y passwordHash");
  }

  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) {
    throw new Error("DATABASE_URL no configurada en este entorno");
  }
  const useSsl = process.env.PGSSL !== "false";
  const pool = new Pool({
    connectionString,
    ssl: useSsl ? { rejectUnauthorized: false } : undefined
  });

  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const { rows } = await client.query("SELECT value FROM sync_kv WHERE key = 'users' FOR UPDATE");
    if (rows.length === 0) {
      throw new Error('No existe la fila "users" en sync_kv');
    }
    const usersValue = rows[0].value;
    const items = asArray(usersValue.items);
    const target = items.find((u) => String(u.username || "").toLowerCase() === username.toLowerCase());
    if (!target) {
      throw new Error(`Usuario "${username}" no encontrado`);
    }
    target.passwordHash = passwordHash;
    const nextValue = { ...usersValue, items };
    await client.query("UPDATE sync_kv SET value = $1 WHERE key = 'users'", [JSON.stringify(nextValue)]);
    await client.query("COMMIT");
    console.log(`OK: contraseña actualizada para el usuario "${target.username}" (id=${target.id}).`);
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
    await pool.end();
  }
}

main().catch((error) => {
  console.error("ERROR:", error.message);
  process.exit(1);
});
