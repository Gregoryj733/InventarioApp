package com.inventario.app.data.repository

import android.content.Context
import android.net.Uri
import com.inventario.app.data.dao.AppMetaDao
import com.inventario.app.data.dao.ProductDao
import com.inventario.app.data.dao.SaleRecordDao
import com.inventario.app.data.dao.UserDao
import com.inventario.app.data.entity.AppMeta
import com.inventario.app.data.entity.Product
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
                    role = UserRole.CONSULTA
                ),
                User(
                    username = "admin",
                    passwordHash = PasswordHasher.hash("admin"),
                    role = UserRole.ADMIN
                )
            )
        )
        // Usuarios demo: consulta/consulta y admin/admin
    }

    suspend fun login(username: String, password: String): User? {
        val normalized = username.trim().lowercase()
        val user = userDao.findByUsername(normalized) ?: return null
        return user.takeIf { PasswordHasher.matches(password, it.passwordHash) }
    }
}

class InventoryRepository(
    private val context: Context,
    private val productDao: ProductDao,
    private val appMetaDao: AppMetaDao,
    private val saleRecordDao: SaleRecordDao,
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
     */
    suspend fun executeOrder(lines: List<OrderLine>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val productsById = lines.associate { line ->
                line.productId to (productDao.findById(line.productId)
                    ?: error("Producto no encontrado: ${line.description}"))
            }
            cloudSync?.pushStockDeductions(lines, productsById)
            productDao.executeOrderDeductions(lines, now)
            val totalUsd = lines.sumOf { it.totalUsd }
            val sale = SaleRecord(
                syncId = InventoryCloudSync.newSyncId(),
                createdAt = now,
                totalUsd = totalUsd
            )
            saleRecordDao.insert(sale)
            cloudSync?.pushSale(sale)
            Unit
        }
    }

    suspend fun totalSalesToday(): Double = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        saleRecordDao.sumTotalUsdBetween(start, end)
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
}
