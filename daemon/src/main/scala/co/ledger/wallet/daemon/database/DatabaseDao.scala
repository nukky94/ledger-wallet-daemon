package co.ledger.wallet.daemon.database

import java.sql.Timestamp
import java.util.{Date, UUID}
import javax.inject.{Inject, Singleton}

import co.ledger.wallet.daemon.database.DBMigrations.Migrations
import co.ledger.wallet.daemon.exceptions._
import co.ledger.wallet.daemon.services.LogMsgMaker
import co.ledger.wallet.daemon.utils.HexUtils
import com.twitter.inject.Logging
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.TransactionIsolation

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DatabaseDao @Inject()(db: Database) extends Logging {
  import database.Tables.profile.api._
  import database.Tables._

  def migrate()(implicit ec: ExecutionContext): Future[Unit] = {
    info("Start database migration")
    val lastMigrationVersion = databaseVersions.sortBy(_.version.desc).map(_.version).take(1).result.head
    db.run(lastMigrationVersion.transactionally) recover {
      case _ => -1
    } flatMap { currentVersion =>
      {
        debug(s"Current database version at ${currentVersion}")
        val maxVersion = Migrations.keys.toArray.sortWith(_ > _).head

        def migrate(version: Int, maxVersion: Int): Future[Unit] = {
          if(version > maxVersion) {
            debug(s"Database version up to date at $version")
            Future.successful()
          } else {
            debug(s"Migrating version $version / $maxVersion")
            val rollbackMigrate = DBIO.seq(Migrations(version), insertDatabaseVersion(version))
            db.run(rollbackMigrate.transactionally).flatMap { _ =>
              debug(s"version $version / $maxVersion migration done")
              migrate(version + 1, maxVersion)
            }
          }
        }
        migrate(currentVersion + 1, maxVersion)
      }
    }
  }

  def deletePool(poolName: String, userId: Long)(implicit ec: ExecutionContext): Future[Option[PoolDto]] = {
    val query = filterPool(poolName, userId)
    val action = for {
      result <- query.result.headOption
      _ <- query.delete
    } yield result
    db.run(action.withTransactionIsolation(TransactionIsolation.RepeatableRead)).map { row =>
      debug(LogMsgMaker.newInstance("Daemon wallet pool deleted")
        .append("pool_name", poolName)
        .append("user_id", userId)
        .append("deleted_row", row)
        .toString())
      row.map(createPool)
    }
  }

  def getPools(userId: Long)(implicit ec: ExecutionContext): Future[Seq[PoolDto]] = {
    val query = pools.filter(pool => pool.userId === userId.bind).sortBy(_.id.desc)
    safeRun(query.result).map { rows =>
      debug(LogMsgMaker.newInstance("Daemon wallet pools retrieved")
        .append("user_id", userId)
        .append("result_row", rows.size)
        .append("result", rows.map(_.name))
        .toString())
      rows.map(createPool)
    }
  }

  def getPool(userId: Long, poolName: String)(implicit ec: ExecutionContext): Future[Option[PoolDto]] = {
    val query = pools.filter(pool => pool.userId === userId && pool.name === poolName).result.headOption
    safeRun(query).map { row =>
      debug(LogMsgMaker.newInstance("Daemon wallet pool retrieved")
        .append("user_id", userId)
        .append("pool_name", poolName)
        .append("result", row)
        .toString())
      row.map(createPool)
    }
  }

  def getUser(targetPubKey: Array[Byte])(implicit ec: ExecutionContext): Future[Option[UserDto]] = {
    getUser(HexUtils.valueOf(targetPubKey))
  }

  def getUser(pubKey: String)(implicit ec: ExecutionContext): Future[Option[UserDto]] = {
    safeRun(filterUser(pubKey).result).map { rows =>
      debug(LogMsgMaker.newInstance("Daemon user retrieved")
        .append("pub_key", pubKey)
        .append("result_row", rows.size)
        .append("result", rows.map(_.id))
        .toString())
      if(rows.isEmpty) None
      else Option(createUser(rows.head))
    }
  }

  def getUsers()(implicit ec: ExecutionContext): Future[Seq[UserDto]] = {
    val query = users.result
    safeRun(query).map { rows =>
      debug(LogMsgMaker.newInstance("Daemon users retrieved")
        .append("result_row", rows.size)
        .append("result", rows.map(_.id))
        .toString())
      rows.map(createUser(_))
    }
  }

  def getNextOperationInfo(next: UUID, userId: Long, poolId: Long, walletName: Option[String], accountIndex: Option[Int])
                          (implicit ec: ExecutionContext): Future[Option[OperationDto]] = {
    val query = operations.filter { op =>
      op.deletedAt.isEmpty &&
      op.next.isDefined && op.next === Option(next.toString) &&
        op.userId === userId &&
        op.poolId === poolId &&
        ((op.walletName.isEmpty && walletName.isEmpty) || (op.walletName === walletName)) &&
        ((op.accountIndex.isEmpty && accountIndex.isEmpty) || (op.accountIndex === accountIndex))
    }
    safeRun(query.result.headOption).map { op =>
      op.map { current =>
        OperationDto(userId, poolId, walletName, accountIndex, Option(next), current.batch + current.offset, current.batch, Option(UUID.randomUUID()))
      }
    }
  }

  def getPreviousOperationInfo(previous: UUID, userId: Long, poolId: Long, walletName: Option[String], accountIndex: Option[Int])
                              (implicit ec: ExecutionContext): Future[Option[OperationDto]] = {
    val query = operations.filter { op =>
      op.deletedAt.isEmpty &&
      op.next.isDefined && op.next === Option(previous.toString) &&
        op.userId === userId &&
        op.poolId === poolId &&
        ((op.walletName.isEmpty && walletName.isEmpty) || (op.walletName === walletName)) &&
        ((op.accountIndex.isEmpty && accountIndex.isEmpty) || (op.accountIndex === accountIndex))
    }
    safeRun(query.result.headOption).map(_.map(createOperation(_)))
  }

  def updateOpsOffset(poolId: Long, walletName: String, accountIndex: Int)
                     (implicit ec: ExecutionContext): Future[Seq[Int]] = {
    val targetRows = operations.filter { op =>
      op.deletedAt.isEmpty &&
      op.poolId === poolId &&
        (op.walletName.isEmpty || op.walletName === Option(walletName)) &&
        (op.accountIndex.isEmpty || op.accountIndex === Option(accountIndex))
    }

    val actions = targetRows.result.flatMap { rows =>
      DBIO.sequence(rows.map { row => (for { o <- operations if o.id === row.id } yield o.offset ).update(row.offset + 1)})
    }
    safeRun(actions).map { list =>
      debug(LogMsgMaker.newInstance("Updated target operations")
        .append("pool_id", poolId)
        .append("wallet_name", walletName)
        .append("account_index", accountIndex)
        .append("updated_rows_count", list.size)
        .toString())
      list
    }
  }

  def insertOperation(operation: OperationDto)(implicit ec: ExecutionContext): Future[Long] = {
    val query = operations.returning(operations.map(_.id)) += createOperationRow(operation)
    safeRun(query).map { id =>
      debug(LogMsgMaker.newInstance("Daemon operation inserted")
        .append("operation", operation)
        .append("inserted_operation_id", id)
        .toString())
      id
    }
  }

  def insertPool(newPool: PoolDto)(implicit ec: ExecutionContext): Future[Long] = {
    val query = filterPool(newPool.name, newPool.userId).exists.result.flatMap { exists =>
      if (!exists) {
        pools.returning(pools.map(_.id)) += createPoolRow(newPool)
      } else {
        DBIO.failed(WalletPoolAlreadyExistException(newPool.name))
      }
    }
    safeRun(query).map { id =>
      debug(LogMsgMaker.newInstance("Daemon wallet pool inserted")
        .append("wallet_pool", newPool)
        .append("inserted_pool_id", id)
        .toString())
      id
    }
  }

  def insertUser(newUser: UserDto)(implicit ec: ExecutionContext): Future[Long] = {
    val query = filterUser(newUser.pubKey).exists.result.flatMap {(exists) =>
      if (!exists) {
        users.returning(users.map(_.id)) += createUserRow(newUser)
      } else {
        DBIO.failed(UserAlreadyExistException(newUser.pubKey))
      }
    }
    safeRun(query).map { id =>
      debug(LogMsgMaker.newInstance("Daemon user inserted")
        .append("user", newUser)
        .append("inserted_user_id", id)
        .toString())
      id
    }
  }

  private def safeRun[R](query: DBIO[R])(implicit ec: ExecutionContext): Future[R] = {
    db.run(query.transactionally).recoverWith {
      case e: DaemonException => Future.failed(e)
      case others: Throwable => Future.failed(DaemonDatabaseException("Failed to run database query", others))
    }
  }

  private def createOperationRow(operation: OperationDto): OperationRow = {
    val currentTime = new Timestamp(new Date().getTime)
    OperationRow(0, operation.userId, operation.poolId, operation.walletName, operation.accountIndex, fromUUID(operation.previous), operation.offset, operation.batch, fromUUID(operation.next), currentTime, None)
  }

  private def createOperation(opRow: OperationRow): OperationDto = {
    OperationDto(opRow.userId, opRow.poolId, opRow.walletName, opRow.accountIndex, toUUID(opRow.previous), opRow.offset, opRow.batch, toUUID(opRow.next), Option(opRow.id))
  }

  private def createPoolRow(pool: PoolDto): PoolRow =
    PoolRow(0, pool.name, pool.userId, new Timestamp(new Date().getTime), pool.configuration, pool.dbBackend, pool.dbConnectString)

  private def createPool(poolRow: PoolRow): PoolDto =
    PoolDto(poolRow.name, poolRow.userId, poolRow.configuration, Option(poolRow.id), poolRow.dbBackend, poolRow.dbConnectString)

  private def createUserRow(user: UserDto): UserRow =
    UserRow(0, user.pubKey, user.permissions)

  private def createUser(userRow: UserRow): UserDto =
    UserDto(userRow.pubKey, userRow.permissions, Option(userRow.id))

  private def insertDatabaseVersion(version: Int): DBIO[Int] =
    databaseVersions += (0, new Timestamp(new Date().getTime))

  private def filterPool(poolName: String, userId: Long) = {
    pools.filter(pool => pool.userId === userId.bind && pool.name === poolName.bind)
  }

  private def filterUser(pubKey: String) = {
    users.filter(_.pubKey === pubKey.bind)
  }

  private def toUUID(str: Option[String]): Option[UUID] = {
    str.map(UUID.fromString(_))
  }

  private def fromUUID(uuid: Option[UUID]): Option[String] = {
    uuid.map(_.toString)
  }

}

case class UserDto(pubKey: String, permissions: Long, id: Option[Long] = None)
case class PoolDto(name: String, userId: Long, configuration: String, id: Option[Long] = None, dbBackend: String = "", dbConnectString: String = "")
case class OperationDto(userId: Long, poolId: Long, walletName: Option[String], accountIndex: Option[Int], previous: Option[UUID], offset: Long, batch: Int, next: Option[UUID], id: Option[Long] = None)