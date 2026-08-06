const { WebSocketServer } = require("ws");
const url = require("url");

let wss = null;

/**
 * Adjunta un servidor WebSocket al mismo servidor HTTP de Express, en la ruta
 * /v1/ws. Sustituye el polling de 15s del cliente: cada mutación relevante
 * llama a broadcast() y todos los dispositivos conectados reaccionan al instante.
 */
function attach(httpServer, apiKey) {
  wss = new WebSocketServer({ noServer: true });

  httpServer.on("upgrade", (request, socket, head) => {
    const { pathname, query } = url.parse(request.url, true);
    if (pathname !== "/v1/ws") {
      socket.destroy();
      return;
    }
    // Acepta la clave tanto por query param (usado por el cliente Android)
    // como por header X-Api-Key, para ser consistentes con el middleware
    // de Express que protege el resto de rutas /v1/*.
    const headerKey = request.headers["x-api-key"];
    if (apiKey && query.apiKey !== apiKey && headerKey !== apiKey) {
      // Responder con un 401 explícito (en vez de solo destruir el socket)
      // para que el cliente reciba un handshake HTTP parseable y pueda
      // mostrar un mensaje de error claro en vez de un corte de conexión.
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, request);
    });
  });

  wss.on("connection", (ws) => {
    ws.isAlive = true;
    ws.on("pong", () => {
      ws.isAlive = true;
    });
  });

  const heartbeat = setInterval(() => {
    wss.clients.forEach((ws) => {
      if (!ws.isAlive) {
        ws.terminate();
        return;
      }
      ws.isAlive = false;
      ws.ping();
    });
  }, 30_000);

  httpServer.on("close", () => clearInterval(heartbeat));

  return wss;
}

/**
 * Notifica a todos los clientes conectados que algo cambió. El cliente decide
 * qué recurso volver a pedir según `type` (inventory | sales | cashClosings | users).
 */
function broadcast(type, payload) {
  if (!wss) return;
  const message = JSON.stringify({ type, ...payload, ts: Date.now() });
  wss.clients.forEach((ws) => {
    if (ws.readyState === ws.OPEN) {
      ws.send(message);
    }
  });
}

module.exports = { attach, broadcast };
