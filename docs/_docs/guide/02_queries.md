---
title: Queries
---

# {{page.title}}

With our user type and table definition in hard, we can start constructing some queries.

```scala 3 sc-name:User.scala
import dataprism.KMacros
import dataprism.sql.{Table, Column}
import dataprism.jdbc.sql.JdbcCodec
import dataprism.jdbc.sql.PostgresJdbcTypes.*

case class UserK[F[_]](
  id: F[Int],
  name: F[Option[String]],
  username: F[String],
  email: F[String]
)

object UserK:
  // Snippet compiler fails here sadly
  given KMacros.ApplyTraverseKC[UserK] = ??? // KMacros.deriveApplyTraverseKC[UserK]

  val table: Table[JdbcCodec, UserK] = Table(
    "users",
    UserK(
      Column("id", integer),
      Column("name", text.nullable),
      Column("username", text),
      Column("email", text)
    )
  )
```

For basically anything that manipulates database values, we need a `QueryPlatform`. Once again, you should select a
platform based on the database and codec type you are using. For these examples I will be using the `PostgresJdbcPlatform`.

## Query.from, map, filter, limit, offset

A query can be constructed using `Query.from(table)`. From there we can do various things on the query, like map,
filter, grouping and more. Here are some simple examples. I will annotate the types for better understanding.

```scala 3 sc-compile-with:User.scala
import dataprism.jdbc.platform.implementations.PostgresJdbcPlatform.*

val q: Query[UserK] = Query.from(UserK.table)

val q2: Query[UserK] = q.map((u: UserK[DbValue]) => 
  u.copy(name = u.name.getOrElse(u.username).asSome)
)

val q3: Query[UserK] = q.filter((u: UserK[DbValue]) => u.name.isDefined)

val v1: DbValue[Long] = q.size

val q4: Query[[F[_]] =>> F[String]] = q.map((u: UserK[DbValue]) => u.username)

val q5: Query[[F[_]] =>> (F[String], F[String])] = q.map(
  (u: UserK[DbValue]) => (u.username, u.email)
)

val q6: Query[UserK] = q.drop(2).limit(5).offset(3)
``` 

Within these queries, values are wrapped in `DbValue`. Note that this type is not present in the actual `Query` type
which only knows about the abstract HKD.

## Joins

Not all queries operate on plain `DbValue`. For example, while normal joins require all values to be present,
other join types might make some values nullable. This is represented by the `Nullable` type.

```scala 3
type Nullable[A] <: Option[_] = A match {
  case Option[b] => Option[b]
  case _ => Option[A]
}
```

`Nullable` wraps a type in `Option` unless it is already. For example,
* `Nullable[Int]` is `Option[Int]`
* `Nullable[Option[Int]]` is `Option[Int]`

We can use this with our `UserK` HKD to make every field `Optional` (take note of how `Nullable` keeps the type of `name` the same).

```scala 3
// A nullable user. That is to say UserK[Nullable] 
case class NullableUser(
  id: Option[Int],
  name: Option[String],
  username: Option[String],
  email: Option[String]
)
```

With that, we can take a look at the `Query` types for joins.

```scala 3 sc-compile-with:User.scala
import perspective.Compose2
import dataprism.jdbc.platform.implementations.PostgresJdbcPlatform.*

val q: Query[UserK] = Query.from(UserK.table)

val q1: Query[[F[_]] =>> (UserK[F], UserK[F])] = q.join(q)(_.username === _.username)

val q2: Query[[F[_]] =>> (UserK[F], UserK[Compose2[F, Nullable]])] =
  q.leftJoin(UserK.table)(_.username === _.username)

val q3: Query[[F[_]] =>> (UserK[Compose2[F, Nullable]], UserK[F])] =
  q.rightJoin(UserK.table)(_.username === _.username)

val q4: Query[[F[_]] =>> (UserK[Compose2[F, Nullable]], UserK[Compose2[F, Nullable]])] =
  q.fullJoin(UserK.table)(_.username === _.username)
```

As can also be seen in this example, you can join on a query, or directly on a table.

## groupMap

The last query function we'll look at is `groupMap`. DataPrism does not expose a traditional `groupBy` function, as
a `groupMap` function maps better to DataPrism's style. `groupMap` takes two functions as arguments. The first one
extracts the value to group by. The second one performs the aggregation given both that extracted value and the values of the query which are wrapped in `Many`. Here are some examples.

```scala 3 sc-compile-with:User.scala
import dataprism.jdbc.platform.implementations.PostgresJdbcPlatform.*

//Needed for arrayAgg currently
import dataprism.jdbc.sql.PostgresJdbcTypes.ArrayMapping.given_ArrayMapping_A

val q: Query[UserK] = Query.from(UserK.table)

val q1: Query[[F[_]] =>> (F[Option[String]], F[Long])] =
  q.groupMap((v: UserK[DbValue]) => v.name)(
    (name: DbValue[Option[String]], v: UserK[Many]) => (name, v.name.count)
  )

val q2: Query[[F[_]] =>> (F[String], F[Seq[String]])] =
  q.groupMap((v: UserK[DbValue]) => v.email)(
    (email: DbValue[String], v: UserK[Many]) => (email, v.username.arrayAgg)
  )

val q3: Query[[F[_]] =>> (F[Option[String]], F[String], F[Seq[String]])] =
  q.groupMap((v: UserK[DbValue]) => (v.name, v.username))(
    (t: (DbValue[Option[String]], DbValue[String]), v: UserK[Many]) => 
      (t._1, t._2, v.email.arrayAgg)
  )
```

Note how you don't have to directly return a column from the grouping function. For example, we return a tuple in `q3`.
Anything that works for `map` works here too (see [Mapping to other Higher Kinded Data](#mapping-to-other-higher-kinded-data)).

## flatMap

So far, we've mostly seen direct function application on the queries. That is the simplest way, but `flatMap`
does also exist, and because of it, for comprehensions.

```scala 3 sc-compile-with:User.scala
import dataprism.jdbc.platform.implementations.PostgresJdbcPlatform.*

// why commented?
//val q1: Query[[F[_]] =>> (UserK[F], UserK[F])] =
//  Query.from(UserK.table).flatMap(u1 => Query.from(UserK.table).map(u2 => (u1, u2)))

val q2: Query[UserK] = for
  u <- Query.from(UserK.table)
  u2 <- Query.from(UserK.table)
  if u.email === u2.email
yield u2
```

<!-- what is the upshot of this? -->

## Mapping to other Higher Kinded Data

Lastly, let's talk a bit about what types you can use in the result of `map` and similar. So far we've seen tuples, and
the HKD we defined our table in. The HKD we used to define our table is not special. Any HKD (or not even HKD)
with `perspective.ApplyKC` and `perspective.TraverseKC` instances can be used as a result type in a map and similar.
Here's one example.

```scala 3 sc-compile-with:User.scala
import dataprism.jdbc.platform.implementations.PostgresJdbcPlatform.*

//Needed for arrayAgg currently
import dataprism.jdbc.sql.PostgresJdbcTypes.ArrayMapping.given_ArrayMapping_A

case class UsersWithEmailK[F[_]](email: F[String], usernames: F[Seq[String]])
object UsersWithEmailK:
  // Snippet compiler fails here sadly
  given KMacros.ApplyTraverseKC[UsersWithEmailK] = ??? // KMacros.deriveApplyTraverseKC[UsersWithEmailK]

val q1: Query[UsersWithEmailK] =
  Query.from(UserK.table).groupMap((v: UserK[DbValue]) => v.email)(
    (email: DbValue[String], v: UserK[Many]) => UsersWithEmailK(email, v.username.arrayAgg)
  )
```
