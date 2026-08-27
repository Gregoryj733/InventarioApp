async function createStore() {
  const forced = String(process.env.STORAGE_BACKEND || "")
    .trim()
    .toLowerCase();

  if (forced === "file") {
    const fileStore = require("./store-file");
    fileStore.init();
    console.log("Storage backend: file (STORAGE_BACKEND=file)");
    return fileStore;
  }

  if (forced === "postgres" && !process.env.DATABASE_URL) {
    throw new Error("STORAGE_BACKEND=postgres requiere DATABASE_URL");
  }

  if (process.env.DATABASE_URL) {
    const pgStore = require("./store-pg");
    const maxAttempts = 5;
    let lastError = null;
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try {
        await pgStore.init();
        console.log("Storage backend: postgres");
        return pgStore;
      } catch (error) {
        lastError = error;
        if (isPostgresFatalError(error)) {
          console.error("Postgres no disponible:", error.message);
          break;
        }
        const delayMs = attempt * 2000;
        console.error(
          `Postgres init failed (attempt ${attempt}/${maxAttempts}):`,
          error.message
        );
        if (attempt >= maxAttempts) {
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, delayMs));
      }
    }

    if (process.env.STORAGE_FALLBACK_FILE === "false") {
      throw lastError || new Error("No se pudo inicializar Postgres");
    }

    console.warn(
      "Postgres inaccesible; usando almacenamiento en archivo. " +
        "En Render free los datos en archivo NO persisten entre reinicios. " +
        "Revisa la cuota de Neon o crea un proyecto nuevo en neon.tech."
    );
  }

  const fileStore = require("./store-file");
  fileStore.init();
  console.log("Storage backend: file");
  return fileStore;
}

/** Errores que no mejoran con reintentos (p. ej. cuota de transferencia de Neon agotada). */
function isPostgresFatalError(error) {
  const msg = String(error?.message || "").toLowerCase();
  const code = String(error?.code || "");
  return (
    code === "53000" ||
    code === "57P01" ||
    msg.includes("data transfer quota") ||
    msg.includes("exceeded the data transfer") ||
    msg.includes("quota") && msg.includes("exceed")
  );
}

module.exports = { createStore };
