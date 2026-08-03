async function createStore() {
  if (process.env.DATABASE_URL) {
    try {
      const pgStore = require("./store-pg");
      await pgStore.init();
      console.log("Storage backend: postgres");
      return pgStore;
    } catch (error) {
      console.error(
        "Postgres init failed, falling back to file store:",
        error.message
      );
    }
  }

  const fileStore = require("./store-file");
  fileStore.init();
  console.log("Storage backend: file");
  return fileStore;
}

module.exports = { createStore };
