FROM node:20-slim

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY server.js store.js store-file.js store-pg.js auth.js excelParser.js realtime.js push.js ./

ENV DATA_DIR=/data
ENV PORT=8787

EXPOSE 8787

CMD ["node", "server.js"]
