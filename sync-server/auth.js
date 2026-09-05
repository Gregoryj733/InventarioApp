const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");

const JWT_SECRET = process.env.JWT_SECRET || "inventario-jwt-dev-secret-change-me";
const TOKEN_TTL = "30d";

function hashPassword(password) {
  return bcrypt.hashSync(password, 10);
}

function verifyPassword(password, hash) {
  if (!hash) return false;
  return bcrypt.compareSync(password, hash);
}

function signToken(user) {
  return jwt.sign(
    {
      sub: String(user.id),
      username: user.username,
      role: user.role,
      sucursal: user.sucursal || ""
    },
    JWT_SECRET,
    { expiresIn: TOKEN_TTL }
  );
}

function verifyToken(token) {
  return jwt.verify(token, JWT_SECRET);
}

/**
 * Middleware Express: exige un JWT válido (emitido por /v1/auth/login) y,
 * opcionalmente, un rol específico. Acepta el token en Authorization o en ?t=
 * (útil para <img src> del portal al imprimir códigos QR).
 */
function requireAuth(roles) {
  const allowedRoles = roles ? [].concat(roles) : null;
  return (req, res, next) => {
    const header = req.get("Authorization") || "";
    let token = header.startsWith("Bearer ") ? header.slice(7).trim() : null;
    if (!token && req.query.t) token = String(req.query.t).trim();
    if (!token) {
      return res.status(401).json({ error: "Sesión requerida" });
    }
    try {
      const payload = verifyToken(token);
      if (allowedRoles) {
        const userRole = String(payload.role || "").toUpperCase();
        const normalizedAllowed = allowedRoles.map((role) => String(role).toUpperCase());
        if (!normalizedAllowed.includes(userRole)) {
          return res.status(403).json({ error: "No tienes permisos para esta acción" });
        }
      }
      req.user = payload;
      next();
    } catch (error) {
      return res.status(401).json({ error: "Sesión inválida o expirada" });
    }
  };
}

module.exports = {
  hashPassword,
  verifyPassword,
  signToken,
  verifyToken,
  requireAuth
};
