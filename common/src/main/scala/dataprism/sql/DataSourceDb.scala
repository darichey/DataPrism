package dataprism.sql

import cats.data.State
import perspective.derivation.{ProductK, ProductKPar}
import perspective.*

import java.io.Closeable
import java.sql.PreparedStatement
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

class DataSourceDb(ds: DataSource & Closeable)(using ExecutionContext) extends Db {

  extension [A: Using.Releasable](a: A)
    def acquired(using man: Using.Manager): A =
      man.acquire(a)
      a

  private def makePrepared[A](sql: SqlStr)(f: Using.Manager ?=> PreparedStatement => A): Future[A] =
    Future {
      Using.Manager { implicit man =>
        println("Making prepared statement")
        println(sql.str)

        val ps = ds.getConnection.acquired.prepareStatement(sql.str).acquired

        for ((obj, i) <- sql.args.zipWithIndex) {
          obj.tpe.set(ps, i + 1, obj.value)
        }

        f(ps)
      }
    }.flatMap(Future.fromTry)

  override def run(sql: SqlStr): Future[Int] = makePrepared(sql)(_.executeUpdate())

  override def runIntoSimple[Res](
      sql: SqlStr,
      dbTypes: DbType[Res]
  ): Future[QueryResult[Res]] =
    runIntoRes[ProductKPar[Tuple1[Res]]](sql, ProductK.of[DbType, Tuple1[Res]](Tuple1(dbTypes)))
      .map(_.map(_.tuple.head))

  override def runIntoRes[Res[_[_]]](
      sql: SqlStr,
      dbTypes: Res[DbType]
  )(using FA: ApplyKC[Res], FT: TraverseKC[Res]): Future[QueryResult[Res[Id]]] =
    makePrepared(sql) { (ps: PreparedStatement) =>
      val rs = ps.executeQuery().acquired

      val indicesState: State[Int, Res[Const[Int]]] =
        dbTypes.traverseK([A] => (_: DbType[A]) => State((acc: Int) => (acc + 1, acc)))

      val indices: Res[Const[Int]] = indicesState.runA(1).value

      val rows = Seq.unfold(rs.next())(cond =>
        Option.when(cond)(
          (
            dbTypes.map2K[Const[Int], Id](indices)(
              [A] => (dbType: DbType[A], idx: Int) => dbType.get(rs, idx)
            ),
            rs.next()
          )
        )
      )

      QueryResult(rows)
    }

  def close(): Unit = ds.close()
}
