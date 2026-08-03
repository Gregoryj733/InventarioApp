async function createStore() {
  if (process.env.DATABASE_URL) {
    const pgStore = require("./store-pg");
    await pgStore.init();
    return pgStore;
  }

  const fileStore = require("./store-file");
  fileStore.init();
  return fileStore;
}

module.exports = { createStore };
