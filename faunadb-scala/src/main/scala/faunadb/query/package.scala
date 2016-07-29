package faunadb

import com.fasterxml.jackson.annotation.JsonValue
import faunadb.values._
import scala.annotation.meta.getter
import scala.language.experimental.macros
import scala.language.implicitConversions

/**
  * Functions modeling the FaunaDB Query language.
  *
  * Instances of these classes can be composed to model a query expression, which can then be passed to
  * [[FaunaClient!.query(expr:faunadb\.query\.Expr)*]] in order to execute the query.
  *
  * ===Examples===
  *
  * {{{
  * val query = Create(Ref("classes/spells"), Obj("data" -> Obj("name" -> "Magic Missile")))
  * }}}
  *
  * {{{
  * val query = Map(Lambda(r => Get(r)), Paginate(Match(Ref("indexes/spells_by_name"), "Magic Missile")))
  * }}}
  */
package query {

  /**
    * A query language expression.
    */
  case class Expr private[query] (@(JsonValue @getter) value: Value) extends AnyVal

  object Expr {
    implicit def boolToExpr(unwrapped: Boolean) = Expr(BooleanV(unwrapped))
    implicit def stringToExpr(unwrapped: String) = Expr(StringV(unwrapped))
    implicit def intToExpr(unwrapped: Int) = Expr(LongV(unwrapped))
    implicit def longToExpr(unwrapped: Long) = Expr(LongV(unwrapped))
    implicit def floatToExpr(unwrapped: Float) = Expr(DoubleV(unwrapped))
    implicit def doubleToExpr(unwrapped: Double) = Expr(DoubleV(unwrapped))
    implicit def refToExpr(unwrapped: RefV) = Expr(unwrapped)
    implicit def nullVToExpr(unwrapped: NullV.type) = Expr(unwrapped)

    // FIXME: not sure if this is the best way to do this... would
    // rather transform the value to a series of object constructions.
    implicit def quotedValue(unwrapped: Value) = Expr(ObjectV("quote" -> unwrapped))
    implicit def quotedResult(unwrapped: Result[Value]) = Expr(ObjectV("quote" -> unwrapped.get))
  }

  /**
    * Enumeration for time units. Used by [[https://faunadb.com/documentation/queries#time_functions]].
    */
  sealed abstract class TimeUnit(val expr: Expr)
  object TimeUnit {
    case object Second extends TimeUnit(Expr(StringV("second")))
    case object Millisecond extends TimeUnit(Expr(StringV("millisecond")))
    case object Microsecond extends TimeUnit(Expr(StringV("microsecond")))
    case object Nanosecond extends TimeUnit(Expr(StringV("nanosecond")))
  }

  /**
    * Enumeration for event action types.
    */
  sealed abstract class Action(val expr: Expr)
  object Action {
    case object Create extends Action(Expr(StringV("create")))
    case object Delete extends Action(Expr(StringV("delete")))
  }

  /**
    * Helper for path syntax
    */
  case class Path private (segments: Expr*) extends AnyVal {
    def /(sub: Path) = Path(segments ++ sub.segments: _*)
  }

  /**
    * Helper for pagination cursors
    */
  sealed trait Cursor
  case class Before(expr: Expr) extends Cursor
  case class After(expr: Expr) extends Cursor
  case object NoCursor extends Cursor
}

package object query {

  // implicit conversions

  implicit def strToPath(str: String) = Path(Expr(StringV(str)))
  implicit def intToPath(int: Int) = Path(Expr(LongV(int)))

  // Helpers

  private def varargs(exprs: Seq[Expr]) =
    exprs match {
      case Seq(e) => e.value
      case es     => ArrayV(es map (_.value): _*)
    }

  private def unwrap(exprs: Seq[Expr]) =
    exprs map { _.value }

  private def unwrapPairs(exprs: Seq[(String, Expr)]) =
    exprs map { t => (t._1, t._2.value) }

  // Values

  /**
    * An RefV value.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#values]]
    */
  def Ref(value: String): Expr =
    Expr(RefV(value))

  /**
    * An Array value.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#values]]
    */
  def Arr(elems: Expr*): Expr =
    Expr(ArrayV(unwrap(elems): _*))

  /**
    * An Object value.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#values]]
    */
  def Obj(pairs: (String, Expr)*): Expr =
    Expr(ObjectV("object" -> ObjectV(unwrapPairs(pairs): _*)))

  // Basic Forms

  /**
    * A Let expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#basic_forms]]
    */

  def Let(block: => Any): Expr = macro QueryMacros.let

  def Let(bindings: Seq[(String, Expr)], in: Expr): Expr =
    Expr(ObjectV("let" -> ObjectV(unwrapPairs(bindings): _*), "in" -> in.value))

  /**
    * A Var expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#basic_forms]]
    */
  def Var(name: String): Expr =
    Expr(ObjectV("var" -> StringV(name)))

  /**
   * An If expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#basic_forms]]
   */
  def If(pred: Expr, `then`: Expr, `else`: Expr): Expr =
    Expr(ObjectV("if" -> pred.value, "then" -> `then`.value, "else" -> `else`.value))

  /**
   * A Do expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#basic_forms]]
   */
  def Do(exprs: Expr*): Expr =
    Expr(ObjectV("do" -> varargs(exprs)))

  /**
   * A Lambda expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#basic_forms]]
   */
  def Lambda(fn: Expr => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr, Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr, Expr, Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda
  def Lambda(fn: (Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr) => Expr): Expr = macro QueryMacros.lambda

  def Lambda(lambda: Expr, expr: Expr) =
    Expr(ObjectV("lambda" -> lambda.value, "expr" -> expr.value))

  // Collection Functions

  /**
   * A Map expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#collection_functions]]
   */
  def Map(lambda: Expr, collection: Expr): Expr =
    Expr(ObjectV("map" -> lambda.value, "collection" -> collection.value))

  /**
   * A Foreach expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#collection_functions]]
   */
  def Foreach(lambda: Expr, collection: Expr): Expr =
    Expr(ObjectV("foreach" -> lambda.value, "collection" -> collection.value))

  /**
    * A Filter expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#collection_functions]]
    */
  def Filter(lambda: Expr, collection: Expr): Expr =
    Expr(ObjectV("filter" -> lambda.value, "collection" -> collection.value))

  /**
    * A Prepend expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#collection_functions]]
    */
  def Prepend(collection: Expr, elems: Expr): Expr =
    Expr(ObjectV("prepend" -> elems.value, "collection" -> collection.value))

  /**
    * An Append expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#collection_functions]]
    */
  def Append(collection: Expr, elems: Expr): Expr =
    Expr(ObjectV("append" -> elems.value, "collection" -> collection.value))

  /**
    * A Take expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#collection_functions]]
    */
  def Take(num: Expr, collection: Expr): Expr =
    Expr(ObjectV("take" -> num.value, "collection" -> collection.value))

  /**
    * A Drop expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#collection_functions]]
    */
  def Drop(num: Expr, collection: Expr): Expr =
    Expr(ObjectV("drop" -> num.value, "collection" -> collection.value))

  // Read Functions

  /**
   * A Get expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#read_functions]]
   */
  def Get(resource: Expr): Expr =
    Expr(ObjectV("get" -> resource.value))

  def Get(resource: Expr, ts: Expr): Expr =
    Expr(ObjectV("get" -> resource.value, "ts" -> ts.value))

  /**
   * A Paginate expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#read_functions]]
   */
  def Paginate(
    resource: Expr,
    cursor: Cursor = NoCursor,
    ts: Expr = Expr(NullV),
    size: Expr = Expr(NullV),
    sources: Expr = Expr(NullV),
    events: Expr = Expr(NullV)): Expr = {

    val call = List.newBuilder[(String, Value)]
    call += "paginate" -> resource.value

    cursor match {
      case b: Before => call += "before" -> b.expr.value
      case a: After => call += "after" -> a.expr.value
      case _ => ()
    }

    val nullExpr = Expr(NullV)

    if (ts != nullExpr) call += "ts" -> ts.value
    if (size != nullExpr) call += "size" -> size.value
    if (events != nullExpr) call += "events" -> events.value
    if (sources != nullExpr) call += "sources" -> sources.value

    Expr(ObjectV(call.result: _*))
  }

  /**
   * An Exists expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#read_functions]]
   */
  def Exists(ref: Expr): Expr =
    Expr(ObjectV("exists" -> ref.value))

  def Exists(ref: Expr, ts: Expr): Expr =
    Expr(ObjectV("exists" -> ref.value, "ts" -> ts.value))

  /**
   * A Count expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#read_functions]]
   */
  def Count(set: Expr): Expr =
    Expr(ObjectV("count" -> set.value))

  def Count(set: Expr, events: Expr): Expr =
    Expr(ObjectV("count" -> set.value, "events" -> events.value))

  // Write Functions

  /**
   * A Create expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#write_functions]]
   */
  def Create(ref: Expr, params: Expr): Expr =
    Expr(ObjectV("create" -> ref.value, "params" -> params.value))

  /**
   * An Update expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#write_functions]]
   */
  def Update(ref: Expr, params: Expr): Expr =
    Expr(ObjectV("update" -> ref.value, "params" -> params.value))

  /**
   * A Replace expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#write_functions]]
   */
  def Replace(ref: Expr, params: Expr): Expr =
    Expr(ObjectV("replace" -> ref.value, "params" -> params.value))

  /**
   * A Delete expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#write_functions]]
   */
  def Delete(ref: Expr): Expr =
    Expr(ObjectV("delete" -> ref.value))

  /**
    * An Insert expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#write_functions]]
    */
  def Insert(ref: Expr, ts: Expr, action: Action, params: Expr): Expr =
    Insert(ref, ts, action.expr, params)

  def Insert(ref: Expr, ts: Expr, action: Expr, params: Expr): Expr =
    Expr(ObjectV("insert" -> ref.value, "ts" -> ts.value, "action" -> action.value, "params" -> params.value))

  /**
    * A Remove expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#write_functions]]
    */
  def Remove(ref: Expr, ts: Expr, action: Action): Expr =
    Remove(ref, ts, action.expr)

  def Remove(ref: Expr, ts: Expr, action: Expr): Expr =
    Expr(ObjectV("remove" -> ref.value, "ts" -> ts.value, "action" -> action.value))

  // Set Constructors

  /**
   * A Match set.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#sets]]
   */
  def Match(index: Expr, terms: Expr*): Expr =
    Expr(ObjectV("match" -> varargs(terms), "index" -> index.value))

  /**
   * A Union set.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#sets]]
   */
  def Union(sets: Expr*): Expr =
    Expr(ObjectV("union" -> varargs(sets)))

  /**
   * An Intersection set.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#sets]]
   */
  def Intersection(sets: Expr*): Expr =
    Expr(ObjectV("intersection" -> varargs(sets)))

  /**
   * A Difference set.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#sets]]
   */
  def Difference(sets: Expr*): Expr =
    Expr(ObjectV("difference" -> varargs(sets)))

  /**
   * A Distinct set.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#sets]]
   */
  def Distinct(set: Expr): Expr =
    Expr(ObjectV("distinct" -> set.value))

  /**
   * A Join set.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#sets]]
   */
  def Join(source: Expr, `with`: Expr): Expr =
    Expr(ObjectV("join" -> source.value, "with" -> `with`.value))

  // Authentication Functions

  /**
    * A Login expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#auth_functions]]
    */
  def Login(ref: Expr, params: Expr): Expr =
    Expr(ObjectV("login" -> ref.value, "params" -> params.value))

  /**
    * A Logout expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#auth_functions]]
    */
  def Logout(invalidateAll: Expr): Expr =
    Expr(ObjectV("logout" -> invalidateAll.value))

  /**
    * An Identify expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#auth_functions]]
    */
  def Identify(ref: Expr, password: Expr): Expr =
    Expr(ObjectV("identify" -> ref.value, "password" -> password.value))

  // String Functions

  /**
   * A Concat expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#string_functions]]
   */
  def Concat(term: Expr): Expr =
    Expr(ObjectV("concat" -> term.value))

  def Concat(term: Expr, separator: Expr): Expr =
    Expr(ObjectV("concat" -> term.value, "separator" -> separator.value))

  /**
   * A Casefold expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#string_functions]]
   */
  def Casefold(term: Expr): Expr =
    Expr(ObjectV("casefold" -> term.value))

  // Time Functions

  /**
    * A Time expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#time_functions]]
    */
  def Time(str: Expr): Expr =
    Expr(ObjectV("time" -> str.value))

  /**
    * An Epoch expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#time_functions]]
    */
  def Epoch(num: Expr, unit: TimeUnit): Expr =
    Epoch(num, unit.expr)

  def Epoch(num: Expr, unit: Expr): Expr =
    Expr(ObjectV("epoch" -> num.value, "unit" -> unit.value))

  /**
    * A Date expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#time_functions]]
    */
  def Date(str: Expr): Expr =
    Expr(ObjectV("date" -> str.value))

  // Misc Functions

  /**
   * An Equals expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
   */
  def Equals(terms: Expr*): Expr =
    Expr(ObjectV("equals" -> varargs(terms)))

  /**
   * A Contains expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
   */
  def Contains(path: Path, in: Expr): Expr =
    Expr(ObjectV("contains" -> varargs(path.segments), "in" -> in.value))

  def Contains(path: Expr, in: Expr): Expr =
    Expr(ObjectV("contains" -> path.value, "in" -> in.value))

  /**
   * A Select expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
   */
  def Select(path: Path, from: Expr): Expr =
    Expr(ObjectV("select" -> varargs(path.segments), "from" -> from.value))

  def Select(path: Path, from: Expr, default: Expr): Expr =
    Expr(ObjectV("select" -> varargs(path.segments), "from" -> from.value, "default" -> default.value))

  def Select(path: Expr, from: Expr): Expr =
    Expr(ObjectV("select" -> path.value, "from" -> from.value))

  def Select(path: Expr, from: Expr, default: Expr): Expr =
    Expr(ObjectV("select" -> path.value, "from" -> from.value, "default" -> default.value))

  /**
   * An Add expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
   */
  def Add(terms: Expr*): Expr =
    Expr(ObjectV("add" -> varargs(terms)))

  /**
   * A Multiply expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
   */
  def Multiply(terms: Expr*): Expr =
    Expr(ObjectV("multiply" -> varargs(terms)))

  /**
   * A Subtract expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
   */
  def Subtract(terms: Expr*): Expr =
    Expr(ObjectV("subtract" -> varargs(terms)))

  /**
   * A Divide expression.
   *
   * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
   */
  def Divide(terms: Expr*): Expr =
    Expr(ObjectV("divide" -> varargs(terms)))

  /**
    * A Modulo expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def Modulo(terms: Expr*): Expr =
    Expr(ObjectV("modulo" -> varargs(terms)))

  /**
    * A LT expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def LT(terms: Expr*): Expr =
    Expr(ObjectV("lt" -> varargs(terms)))

  /**
    * A LTE expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def LTE(terms: Expr*): Expr =
    Expr(ObjectV("lte" -> varargs(terms)))

  /**
    * A GT expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def GT(terms: Expr*): Expr =
    Expr(ObjectV("gt" -> varargs(terms)))

  /**
    * A GTE expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def GTE(terms: Expr*): Expr =
    Expr(ObjectV("gte" -> varargs(terms)))

  /**
    * An And expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def And(terms: Expr*): Expr =
    Expr(ObjectV("and" -> varargs(terms)))

  /**
    * An Or expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def Or(terms: Expr*): Expr =
    Expr(ObjectV("or" -> varargs(terms)))

  /**
    * A Not expression.
    *
    * '''Reference''': [[https://faunadb.com/documentation/queries#misc_functions]]
    */
  def Not(term: Expr): Expr =
    Expr(ObjectV("not" -> term.value))
}
