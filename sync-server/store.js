async function createStore() {
  if (process.env.DATABASE_URL) {
    const pgStore = require("./store-pg");
    const maxAttempts = 5;
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try {
        await pgStore.init();
        console.log("Storage backend: postgres");
        return pgStore;
      } catch (error) {
        const delayMs = attempt * 2000;
        console.error(
          `Postgres init failed (attempt ${attempt}/${maxAttempts}):`,
          error.message
        );
        if (attempt >= maxAttempts) {
          throw error;
        }
        await new Promise((resolve) => setTimeout(resolve, delayMs));
      }
    }
  }

  const fileStore = require("./store-file");
  fileStore.init();
  console.log("Storage backend: file");
  return fileStore;
}

module.exports = { createStore };
