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
 * opcionalmente, un rol específico. Se aplica ENCIMA del chequeo global de
 * X-Api-Key ya existente en server.js.
 */
function requireAuth(roles) {
  const allowedRoles = roles ? [].concat(roles) : null;
  return (req, res, next) => {
    const header = req.get("Authorization") || "";
    const token = header.startsWith("Bearer ") ? header.slice(7).trim() : null;
    if (!token) {
      return res.status(401).json({ error: "Sesión requerida" });
    }
    try {
      const payload = verifyToken(token);
      if (allowedRoles && !allowedRoles.includes(payload.role)) {
        return res.status(403).json({ error: "No tienes permisos para esta acción" });
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
