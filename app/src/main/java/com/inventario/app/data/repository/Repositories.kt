package com.inventario.app.data.repository

import android.content.Context
import android.net.Uri
import com.inventario.app.data.dao.AppMetaDao
import com.inventario.app.data.dao.CashClosingRecordDao
import com.inventario.app.data.dao.ProductDao
import com.inventario.app.data.dao.SaleLineItemDao
import com.inventario.app.data.dao.SaleRecordDao
import com.inventario.app.data.dao.UserDao
import com.inventario.app.data.entity.AppMeta
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.SaleLineItem
import com.inventario.app.data.entity.SaleRecord
import com.inventario.app.data.entity.User
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.excel.ExcelImporter
import com.inventario.app.data.excel.ImportResult
import com.inventario.app.data.order.OrderLine
import com.inventario.app.data.search.ProductSearch
import com.inventario.app.data.security.PasswordHasher
import com.inventario.app.data.sync.InventoryCloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class LoginStatus {
    SUCCESS,
    INVALID,
    INACTIVE
}

class AuthRepository(
    private val userDao: UserDao
) {
    suspend fun ensureDefaultUsers() {
        if (userDao.count() > 0) return
        userDao.insertAll(
            listOf(
                User(
                    username = "consulta",
                    passwordHash = PasswordHasher.hash("consulta"),
                    role = UserRole.CONSULTA,
                    active = true
                ),
                User(
                    username = "admin",
                    passwordHash = PasswordHasher.hash("admin"),
                    role = UserRole.ADMIN,
                    active = true
                )
            )
        )
        // Usuarios demo: consulta/consulta y admin/admin
    }

    suspend fun login(username: String, password: String): User? {
        val normalized = username.trim().lowercase()
        val user = userDao.findByUsername(normalized) ?: return null
        if (!user.active) return null
        return user.takeIf { PasswordHasher.matches(password, it.passwordHash) }
    }

    suspend fun loginStatus(username: String, password: String): LoginStatus {
        val normalized = username.trim().lowercase()
        val user = userDao.findByUsername(normalized) ?: return LoginStatus.INVALID
        if (!PasswordHasher.matches(password, user.passwordHash)) return LoginStatus.INVALID
        if (!user.active) return LoginStatus.INACTIVE
        return LoginStatus.SUCCESS
    }

    suspend fun listConsultaUsers(): List<User> = withContext(Dispatchers.IO) {
        userDao.listByRole(UserRole.CONSULTA)
    }

    suspend fun createConsultaUser(username: String, password: String, sucursal: String): Result<User> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = username.trim().lowercase()
                val branch = sucursal.trim()
                require(normalized.length >= 3) { "El usuario debe tener al menos 3 caracteres." }
                require(password.length >= 4) { "La contraseña debe tener al menos 4 caracteres." }
                require(branch.isNotBlank()) { "Indica la sucursal del usuario." }
                require(normalized != "admin") { "Ese nombre de usuario no está permitido." }
                if (userDao.findByUsername(normalized) != null) {
                    error("El usuario \"$normalized\" ya existe.")
                }
                val user = User(
                    username = normalized,
                    passwordHash = PasswordHasher.hash(password),
                    role = UserRole.CONSULTA,
                    active = true,
                    sucursal = branch
                )
                userDao.insert(user)
                userDao.findByUsername(normalized) ?: user
            }
        }

    suspend fun deleteConsultaUser(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val deleted = userDao.deleteByIdAndRole(id, UserRole.CONSULTA)
            if (deleted == 0) error("No se pudo eliminar el usuario.")
        }
    }

    suspend fun setConsultaUserActive(id: Long, active: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val updated = userDao.setActive(id, UserRole.CONSULTA, active)
                if (updated == 0) error("No se pudo actualizar el usuario.")
            }
        }

    suspend fun assignConsultaUserSucursal(id: Long, sucursal: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val branch = sucursal.trim()
                require(branch.isNotBlank()) { "Indica la sucursal del usuario." }
                val updated = userDao.setSucursal(id, UserRole.CONSULTA, branch)
                if (updated == 0) error("No se pudo asignar la sucursal.")
            }
        }
}

class InventoryRepository(
    private val context: Context,
    private val productDao: ProductDao,
    private val appMetaDao: AppMetaDao,
    private val saleRecordDao: SaleRecordDao,
    private val saleLineItemDao: SaleLineItemDao,
    private val cashClosingRecordDao: CashClosingRecordDao,
    private var cloudSync: InventoryCloudSync?
) {
    fun setCloudSync(sync: InventoryCloudSync?) {
        cloudSync = sync
    }
    suspend fun search(query: String): List<Product> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val tokens = ProductSearch.tokenize(q)
        val primaryToken = tokens.firstOrNull() ?: return@withContext emptyList()
        val escaped = ProductSearch.escapeLike(primaryToken)

        val likeResults = productDao.searchLike(escaped, limit = 80)
            .filter { product -> ProductSearch.matchesAllTokens(product.description, q) }
            .take(40)
        if (likeResults.isNotEmpty()) return@withContext likeResults

        val ftsQuery = ProductSearch.toFtsQuery(q)
        if (ftsQuery != null) {
            runCatching { productDao.searchFts(ftsQuery, limit = 40) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    suspend fun suggestions(query: String): List<String> =
        search(query).map { it.description }.distinct().take(12)

    suspend fun replaceInventoryFromExcel(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return@withContext ImportResult(0, 0, listOf("No se pudo abrir el archivo."))

        stream.use { input ->
            val (products, result) = ExcelImporter.importProductsWithData(input)
            if (products.isEmpty()) return@withContext result

            val now = System.currentTimeMillis()
            val productsWithIds = products.map {
                Product(
                    syncId = InventoryCloudSync.newSyncId(),
                    description = it.description,
                    quantity = it.quantity,
                    unit = it.unit,
                    price = it.price,
                    updatedAt = now
                )
            }

            productDao.replaceAll(productsWithIds)
            val meta = appMetaDao.get() ?: AppMeta()
            val updatedMeta = meta.copy(lastInventoryUpdateAt = now)
            appMetaDao.upsert(updatedMeta)
            cloudSync?.pushInventoryReplace(productsWithIds, updatedMeta)
            result
        }
    }

    suspend fun productCount(): Int = productDao.count()

    fun observeAllProducts() = productDao.observeAll()

    fun observeMeta() = appMetaDao.observe()

    suspend fun saveBcvRate(rate: Double) {
        val meta = appMetaDao.get() ?: AppMeta()
        val updated = meta.copy(
            bcvRate = rate,
            bcvFetchedAt = System.currentTimeMillis()
        )
        appMetaDao.upsert(updated)
        cloudSync?.pushMeta(updated)
    }

    fun observeCloudSyncStatus() = cloudSync?.status

    suspend fun ensureMeta() {
        if (appMetaDao.get() == null) {
            appMetaDao.upsert(AppMeta())
        }
    }

    suspend fun rebuildSearchIndex() = withContext(Dispatchers.IO) {
        if (productDao.count() > 0) {
            runCatching { productDao.rebuildFtsIndex() }
        }
    }

    suspend fun findProduct(id: Long): Product? = withContext(Dispatchers.IO) {
        productDao.findById(id)
    }

    /**
     * Descuenta stock por cada línea del pedido. Falla si algún producto no tiene stock suficiente.
     * La sincronización en nube es en segundo plano: el pedido se completa aunque el servidor no responda.
     */
    suspend fun executeOrder(lines: List<OrderLine>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val productsById = lines.associate { line ->
                line.productId to (productDao.findById(line.productId)
                    ?: error("Producto no encontrado: ${line.description}"))
            }
            productDao.executeOrderDeductions(lines, now)
            val totalUsd = lines.sumOf { it.totalUsd }
            val rate = appMetaDao.get()?.bcvRate ?: 0.0
            val sale = SaleRecord(
                syncId = InventoryCloudSync.newSyncId(),
                createdAt = now,
                totalUsd = totalUsd,
                bcvRate = rate
            )
            val saleId = saleRecordDao.insert(sale)
            val lineItems = lines.map { line ->
                SaleLineItem(
                    saleRecordId = saleId,
                    productId = line.productId,
                    description = line.description,
                    quantity = line.quantity,
                    unit = line.unit,
                    unitPriceUsd = line.unitPriceUsd,
                    totalUsd = line.totalUsd,
                    createdAt = now
                )
            }
            if (lineItems.isNotEmpty()) {
                saleLineItemDao.insertAll(lineItems)
            }
            cloudSync?.scheduleOrderSync(lines, productsById, sale)
            Unit
        }
    }

    suspend fun totalSalesToday(): Double = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        saleRecordDao.sumTotalUsdBetween(start, end)
    }

    suspend fun confirmedOrdersToday(): Int = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        saleRecordDao.countBetween(start, end)
    }

    suspend fun resetTodayOrders() = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        saleRecordDao.deleteBetween(start, end)
    }

    suspend fun saveCashClosing(record: CashClosingRecord): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val username = record.username.trim().lowercase()
            val (dayStart, dayEnd) = todayBounds()
            val maxRevision = cashClosingRecordDao.maxRevisionByUserBetween(username, dayStart, dayEnd)
            val latest = cashClosingRecordDao.latestByUserBetween(username, dayStart, dayEnd)

            when (latest?.status) {
                CashClosingStatus.APPROVED ->
                    error("Tu cierre de caja de hoy ya fue aprobado. No puedes registrar otro.")
                CashClosingStatus.PENDING -> {
                    if (maxRevision >= InventoryRepository.MAX_CLOSINGS_PER_DAY) {
                        error("Has alcanzado el máximo de ${InventoryRepository.MAX_CLOSINGS_PER_DAY} intentos de cierre por día.")
                    }
                    val updated = record.copy(
                        id = latest.id,
                        status = CashClosingStatus.PENDING,
                        revisionNumber = latest.revisionNumber + 1,
                        reviewedBy = "",
                        reviewedAt = 0L
                    )
                    cashClosingRecordDao.update(updated)
                    latest.id
                }
                CashClosingStatus.REVERTED -> {
                    cashClosingRecordDao.insert(
                        record.copy(
                            username = username,
                            status = CashClosingStatus.PENDING,
                            revisionNumber = maxRevision + 1,
                            reviewedBy = "",
                            reviewedAt = 0L
                        )
                    )
                }
                CashClosingStatus.REJECTED, null -> {
                    if (maxRevision >= InventoryRepository.MAX_CLOSINGS_PER_DAY) {
                        error("Has alcanzado el máximo de ${InventoryRepository.MAX_CLOSINGS_PER_DAY} intentos de cierre por día.")
                    }
                    cashClosingRecordDao.insert(
                        record.copy(
                            username = username,
                            status = CashClosingStatus.PENDING,
                            revisionNumber = maxRevision + 1,
                            reviewedBy = "",
                            reviewedAt = 0L
                        )
                    )
                }
            }
        }
    }

    suspend fun cashClosingAlertForUser(username: String): CashClosingAlertType? =
        withContext(Dispatchers.IO) {
            val normalized = username.trim().lowercase()
            val (dayStart, dayEnd) = todayBounds()
            val latest = cashClosingRecordDao.latestByUserBetween(normalized, dayStart, dayEnd)
            when (latest?.status) {
                CashClosingStatus.REJECTED -> CashClosingAlertType.REJECTED_RESUBMIT
                CashClosingStatus.APPROVED -> CashClosingAlertType.APPROVED_SUCCESS
                else -> null
            }
        }

    suspend fun hasPendingClosings(): Boolean = withContext(Dispatchers.IO) {
        val (dayStart, dayEnd) = todayBounds()
        cashClosingRecordDao.listBalancedPendingBetween(dayStart, dayEnd).isNotEmpty() ||
            cashClosingRecordDao.listDifferencePendingBetween(dayStart, dayEnd).isNotEmpty()
    }

    suspend fun latestClosingToday(username: String): CashClosingRecord? = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val (dayStart, dayEnd) = todayBounds()
        cashClosingRecordDao.latestByUserBetween(normalized, dayStart, dayEnd)
    }

    suspend fun maxRevisionToday(username: String): Int = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val (dayStart, dayEnd) = todayBounds()
        cashClosingRecordDao.maxRevisionByUserBetween(normalized, dayStart, dayEnd)
    }

    suspend fun currentBcvRate(): Double? = withContext(Dispatchers.IO) {
        appMetaDao.get()?.bcvRate
    }

    private fun todayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        return start to end
    }

    companion object {
        const val MAX_CLOSINGS_PER_DAY = 5
    }
}
