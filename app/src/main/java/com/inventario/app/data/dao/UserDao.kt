package com.inventario.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inventario.app.data.entity.User
import com.inventario.app.data.entity.UserRole

@Dao
interface UserDao {
    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): User?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): User?

    @Query("SELECT * FROM users WHERE role = :role ORDER BY username")
    suspend fun listByRole(role: UserRole): List<User>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: User)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<User>)

    @Query("DELETE FROM users WHERE id = :id AND role = :role")
    suspend fun deleteByIdAndRole(id: Long, role: UserRole): Int

    @Query("UPDATE users SET active = :active WHERE id = :id AND role = :role")
    suspend fun setActive(id: Long, role: UserRole, active: Boolean): Int
}
