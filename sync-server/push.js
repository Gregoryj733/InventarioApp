let messaging = null;

/**
 * Inicializa Firebase Admin SDK solo si hay credenciales configuradas
 * (variable de entorno FIREBASE_SERVICE_ACCOUNT con el JSON de la cuenta de
 * servicio). Sin esa variable, las notificaciones simplemente no se envían
 * y el resto del servidor sigue funcionando con normalidad.
 */
function init() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) {
    console.log("FIREBASE_SERVICE_ACCOUNT no configurada; notificaciones push deshabilitadas");
    return;
  }
  try {
    const admin = require("firebase-admin");
    const serviceAccount = JSON.parse(raw);
    if (!admin.apps.length) {
      admin.initializeApp({
        credential: admin.credential.cert(serviceAccount)
      });
    }
    messaging = admin.messaging();
    console.log("Firebase Admin inicializado; notificaciones push habilitadas");
  } catch (error) {
    console.error("No se pudo inicializar Firebase Admin", error.message);
    messaging = null;
  }
}

const TOPIC = process.env.FIREBASE_TOPIC || "inventario_actualizado";

async function sendInventoryUpdatedNotification({ imported, skipped } = {}) {
  if (!messaging) return;
  try {
    await messaging.send({
      topic: TOPIC,
      notification: {
        title: "Inventario actualizado",
        body:
          imported != null
            ? `Se actualizaron ${imported} productos${skipped ? ` (${skipped} omitidos)` : ""}.`
            : "El administrador actualizó el inventario."
      },
      data: {
        type: "inventory_updated",
        ts: String(Date.now())
      }
    });
  } catch (error) {
    console.error("Error enviando notificación push", error.message);
  }
}

async function sendSalesUpdatedNotification() {
  if (!messaging) return;
  try {
    await messaging.send({
      topic: TOPIC,
      data: {
        type: "sales_updated",
        ts: String(Date.now())
      }
    });
  } catch (error) {
    console.error("Error enviando notificación de ventas", error.message);
  }
}

async function sendCashClosingsUpdatedNotification() {
  if (!messaging) return;
  try {
    await messaging.send({
      topic: TOPIC,
      data: {
        type: "cash_closings_updated",
        ts: String(Date.now())
      }
    });
  } catch (error) {
    console.error("Error enviando notificación de cierres de caja", error.message);
  }
}

/** Tasa BCV / meta: solo datos, sin banner (no molesta a usuarios en venta). */
async function sendMetaUpdatedNotification() {
  if (!messaging) return;
  try {
    await messaging.send({
      topic: TOPIC,
      data: {
        type: "meta_updated",
        ts: String(Date.now())
      }
    });
  } catch (error) {
    console.error("Error enviando notificación de meta", error.message);
  }
}

module.exports = {
  init,
  sendInventoryUpdatedNotification,
  sendSalesUpdatedNotification,
  sendCashClosingsUpdatedNotification,
  sendMetaUpdatedNotification,
  TOPIC
};
