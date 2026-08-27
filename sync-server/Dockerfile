FROM node:20-slim

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY server.js store.js store-file.js store-pg.js auth.js excelParser.js realtime.js push.js ./
COPY scripts ./scripts
COPY data ./data
COPY public ./public

ENV DATA_DIR=/data
ENV PORT=8787
# Redeploy trigger 2026-08-27: apply STORAGE_BACKEND=file env on Supra Parts

EXPOSE 8787

CMD ["node", "server.js"]
