package faunadb

import faunadb.errors.{ BadRequestException, NotFoundException, PermissionDeniedException, UnauthorizedException }
import faunadb.query.TimeUnit
import faunadb.query._
import faunadb.values._
import java.time.{ Instant, LocalDate }
import java.time.ZoneOffset.UTC
import java.time.temporal.ChronoUnit
import org.scalatest.{ BeforeAndAfterAll, FlatSpec, Matchers }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Random

class ClientSpec extends FlatSpec with Matchers with BeforeAndAfterAll {
  val config = {
    val rootKey = Option(System.getenv("FAUNA_ROOT_KEY")) getOrElse {
      throw new RuntimeException("FAUNA_ROOT_KEY must defined to run tests")
    }
    val domain = Option(System.getenv("FAUNA_DOMAIN")) getOrElse { "db.fauna.com" }
    val scheme = Option(System.getenv("FAUNA_SCHEME")) getOrElse { "https" }
    val port = Option(System.getenv("FAUNA_PORT")) getOrElse { "443" }

    collection.Map("root_token" -> rootKey, "root_url" -> s"${scheme}://${domain}:${port}")
  }

  val rootClient = FaunaClient(endpoint = config("root_url"), secret = config("root_token"))

  val testDbName = "faunadb-scala-test"
  var client: FaunaClient = null
  var adminClient: FaunaClient = null

  // Helper fields

  val RefField = Field("ref").to[RefV]
  val TsField = Field("ts").to[Long]
  val SecretField = Field("secret").to[String]
  val DataField = Field("data")

  // Page helpers
  case class Ev(ref: RefV, ts: Long, action: String)

  val EventField = Field.zip(
    Field("document").to[RefV],
    Field("ts").to[Long],
    Field("action").to[String]
  ) map { case (r, ts, a) => Ev(r, ts, a) }

  val PageEvents = DataField.collect(EventField)
  val PageRefs = DataField.to[Seq[RefV]]

  def await[T](f: Future[T]) = Await.result(f, 5.second)
  def ready[T](f: Future[T]) = Await.ready(f, 5.second)

  def dropDB(): Unit =
    ready(rootClient.query(Delete(Database(testDbName))))

  // tests

  override protected def beforeAll(): Unit = {
    dropDB()

    val db = await(rootClient.query(CreateDatabase(Obj("name" -> testDbName))))
    val dbRef = db(RefField).get
    val serverKey = await(rootClient.query(CreateKey(Obj("database" -> dbRef, "role" -> "server"))))
    val adminKey = await(rootClient.query(CreateKey(Obj("database" -> dbRef, "role" -> "admin"))))

    client = FaunaClient(endpoint = config("root_url"), secret = serverKey(SecretField).get)
    adminClient = FaunaClient(endpoint = config("root_url"), secret = adminKey(SecretField).get)

    await(client.query(CreateCollection(Obj("name" -> "spells"))))

    await(client.query(CreateIndex(Obj(
      "name" -> "spells_by_element",
      "source" -> Collection("spells"),
      "terms" -> Arr(Obj("field" -> Arr("data", "element"))),
      "active" -> true))))
  }

  override protected def afterAll(): Unit = {
    dropDB()
    client.close()
    adminClient.close()
    rootClient.close()
  }

  "Fauna Client" should "should not find an instance" in {
    a[NotFoundException] should be thrownBy await(client.query(Get(RefV("1234", RefV("spells", Native.Collections)))))
  }

  it should "abort the execution" in {
    a[BadRequestException] should be thrownBy await(client.query(Abort("a message")))
  }

  it should "echo values" in {
    await(client.query(ObjectV("foo" -> StringV("bar")))) should equal (ObjectV("foo" -> StringV("bar")))
    await(client.query("qux")) should equal (StringV("qux"))
  }

  it should "fail with permission denied" in {
    val key = await(rootClient.query(CreateKey(Obj("database" -> Database(testDbName), "role" -> "client"))))
    val client = FaunaClient(endpoint = config("root_url"), secret = key(SecretField).get)

    an[PermissionDeniedException] should be thrownBy await(client.query(Paginate(Native.Databases)))
  }

  it should "fail with unauthorized" in {
    val badClient = FaunaClient(endpoint = config("root_url"), secret = "notavalidsecret")
    an[UnauthorizedException] should be thrownBy await(badClient.query(Get(RefV("12345", RefV("spells", Native.Collections)))))
  }

  it should "create a new instance" in {
    val inst = await(client.query(
      Create(Collection("spells"),
        Obj("data" -> Obj("testField" -> "testValue")))))

    inst(RefField).get.collection should equal (Some(RefV("spells", Native.Collections)))
    inst(RefField).get.database should be (None)
    inst("data", "testField").to[String].get should equal ("testValue")

    await(client.query(Exists(inst(RefField)))) should equal (TrueV)

    val inst2 = await(client.query(Create(Collection("spells"),
      Obj("data" -> Obj(
        "testData" -> Obj(
          "array" -> Arr(1, "2", 3.4),
          "bool" -> true,
          "num" -> 1234,
          "string" -> "sup",
          "float" -> 1.234,
          "null" -> NullV))))))

    val testData = inst2("data", "testData")

    testData.isDefined shouldBe true
    testData("array", 0).to[Long].get shouldBe 1
    testData("array", 1).to[String].get shouldBe "2"
    testData("array", 2).to[Double].get shouldBe 3.4
    testData("string").to[String].get shouldBe "sup"
    testData( "num").to[Long].get shouldBe 1234
  }

  it should "issue a batched query" in {
    val randomText1 = Random.alphanumeric.take(8).mkString
    val randomText2 = Random.alphanumeric.take(8).mkString
    val collectionRef = Collection("spells")
    val expr1 = Create(collectionRef, Obj("data" -> Obj("queryTest1" -> randomText1)))
    val expr2 = Create(collectionRef, Obj("data" -> Obj("queryTest1" -> randomText2)))

    val results = await(client.query(Seq(expr1, expr2)))

    results.length shouldBe 2
    results(0)("data", "queryTest1").to[String].get shouldBe randomText1
    results(1)("data", "queryTest1").to[String].get shouldBe randomText2
  }

  it should "get at timestamp" in {
    val randomCollectionName = Random.alphanumeric.take(8).mkString
    val randomCollection = await(client.query(CreateCollection(Obj("name" -> randomCollectionName))))

    val data = await(client.query(Create(randomCollection(RefField).get, Obj("data" -> Obj("x" -> 1)))))
    val dataRef = data(RefField).get

    val ts1 = data(TsField).get
    val ts2 = await(client.query(Update(dataRef, Obj("data" -> Obj("x" -> 2)))))(TsField).get
    val ts3 = await(client.query(Update(dataRef, Obj("data" -> Obj("x" -> 3)))))(TsField).get

    val xField = Field("data", "x").to[Long]

    await(client.query(At(ts1, Get(dataRef))))(xField).get should equal(1)
    await(client.query(At(ts2, Get(dataRef))))(xField).get should equal(2)
    await(client.query(At(ts3, Get(dataRef))))(xField).get should equal(3)
  }

  it should "issue a paginated query" in {
    val randomCollectionName = Random.alphanumeric.take(8).mkString
    val randomCollectionF = client.query(CreateCollection(Obj("name" -> randomCollectionName)))
    val collectionRef = await(randomCollectionF)(RefField).get

    val randomCollectionIndexF = client.query(CreateIndex(Obj(
      "name" -> (randomCollectionName + "_collection_index"),
      "source" -> collectionRef,
      "active" -> true,
      "unique" -> false
    )))

    val indexCreateF = client.query(CreateIndex(Obj(
      "name" -> (randomCollectionName + "_test_index"),
      "source" -> collectionRef,
      "terms" -> Arr(Obj("field" -> Arr("data", "queryTest1"))),
      "active" -> true,
      "unique" -> false
    )))

    val randomCollectionIndex = await(randomCollectionIndexF)(RefField).get
    val testIndex = await(indexCreateF)(RefField).get

    val randomText1 = Random.alphanumeric.take(8).mkString
    val randomText2 = Random.alphanumeric.take(8).mkString
    val randomText3 = Random.alphanumeric.take(8).mkString

    val createFuture = client.query(Create(collectionRef, Obj("data" -> Obj("queryTest1" -> randomText1))))
    val createFuture2 = client.query(Create(collectionRef, Obj("data" -> Obj("queryTest1" -> randomText2))))
    val createFuture3 = client.query(Create(collectionRef, Obj("data" -> Obj("queryTest1" -> randomText3))))

    val create1 = await(createFuture)
    val create2 = await(createFuture2)
    val create3 = await(createFuture3)

    val queryMatchF = client.query(Paginate(Match(testIndex, randomText1)))
    val queryMatchR = await(queryMatchF)

    queryMatchR(PageRefs).get shouldBe Seq(create1(RefField).get)

    val queryF = client.query(Paginate(Match(randomCollectionIndex), size = 1))
    val resp = await(queryF)

    resp("data").to[ArrayV].get.elems.size shouldBe 1
    resp("after").isDefined should equal (true)
    resp("before").isDefined should equal (false)

    val query2F = client.query(Paginate(Match(randomCollectionIndex), After(resp("after")), size = 1))
    val resp2 = await(query2F)

    resp2("data").to[Seq[Value]].get.size shouldBe 1
    resp2("after").isDefined should equal (true)
    resp2("before").isDefined should equal (true)
  }

  it should "handle a constraint violation" in {
    val randomCollectionName = Random.alphanumeric.take(8).mkString
    val randomCollectionF = client.query(CreateCollection(Obj("name" -> randomCollectionName)))
    val collectionRef = await(randomCollectionF)(RefField).get

    val uniqueIndexFuture = client.query(CreateIndex(Obj(
      "name" -> (randomCollectionName+"_by_unique_test"),
      "source" -> collectionRef,
      "terms" -> Arr(Obj("field" -> Arr("data", "uniqueTest1"))),
      "unique" -> true, "active" -> true)))

    await(uniqueIndexFuture)

    val randomText = Random.alphanumeric.take(8).mkString
    val createFuture = client.query(Create(collectionRef, Obj("data" -> Obj("uniqueTest1" -> randomText))))

    await(createFuture)

    val createFuture2 = client.query(Create(collectionRef, Obj("data" -> Obj("uniqueTest1" -> randomText))))

    val exception = intercept[BadRequestException] {
      await(createFuture2)
    }

    exception.errors(0).code shouldBe "instance not unique"
  }

  it should "test types" in {
    val setF = client.query(Match(Index("spells_by_element"), "arcane"))
    val set = await(setF).to[SetRefV].get
    set.parameters("match").to[RefV].get shouldBe RefV("spells_by_element", Native.Indexes)
    set.parameters("terms").to[String].get shouldBe "arcane"

    await(client.query(Array[Byte](0x1, 0x2, 0x3, 0x4))) should equal (BytesV(0x1, 0x2, 0x3, 0x4))
  }

  it should "test basic forms" in {
    val letF = client.query(Let { val x = 1; val y = Add(x, 2); y })
    val letR = await(letF)
    letR.to[Long].get shouldBe 3

    val ifF = client.query(If(true, "was true", "was false"))
    val ifR = await(ifF)
    ifR.to[String].get shouldBe "was true"

    val randomNum = Math.abs(Random.nextLong() % 250000L) + 250000L
    val randomRef = RefV(randomNum.toString, RefV("spells", Native.Collections))
    val doF = client.query(Do(
      Create(randomRef, Obj("data" -> Obj("name" -> "Magic Missile"))),
      Get(randomRef)
    ))
    val doR = await(doF)
    doR(RefField).get shouldBe randomRef

    val objectF = client.query(Obj("name" -> "Hen Wen", "age" -> 123))
    val objectR = await(objectF)
    objectR("name").to[String].get shouldBe "Hen Wen"
    objectR("age").to[Long].get shouldBe 123

  }

  it should "test collections" in {
    val mapR = await(client.query(Map(Arr(1L, 2L, 3L), Lambda(i => Add(i, 1L)))))
    mapR.to[Seq[Long]].get shouldBe Seq(2, 3, 4)

    val foreachR = await(client.query(Foreach(Arr("Fireball Level 1", "Fireball Level 2"), Lambda(spell => Create(Collection("spells"), Obj("data" -> Obj("name" -> spell)))))))
    foreachR.to[Seq[String]].get shouldBe Seq("Fireball Level 1", "Fireball Level 2")

    val filterR = await(client.query(Filter(Arr(1, 2, 3, 4), Lambda(i => If(Equals(Modulo(i, 2), 0), true, false)))))
    filterR.to[Seq[Long]].get shouldBe Seq(2, 4)

    val takeR = await(client.query(Take(2, Arr(1, 2, 3, 4))))
    takeR.to[Seq[Long]].get shouldBe Seq(1, 2)

    val dropR = await(client.query(Drop(2, Arr(1, 2, 3, 4))))
    dropR.to[Seq[Long]].get shouldBe Seq(3, 4)

    val prependR = await(client.query(Prepend(Arr(1, 2, 3), Arr(4, 5, 6))))
    prependR.to[Seq[Long]].get shouldBe Seq(1, 2, 3, 4, 5, 6)

    val appendR = await(client.query(Append(Arr(1, 2, 3), Arr(4, 5, 6))))
    appendR.to[Seq[Long]].get shouldBe Seq(4, 5, 6, 1, 2, 3)

    val randomElement = Random.alphanumeric.take(8).mkString
    await(client.query(Create(Collection("spells"), Obj("data" -> Obj("name" -> "predicate test", "element" -> randomElement)))))

    //arrays
    await(client.query(IsEmpty(Arr(1, 2, 3)))).to[Boolean].get shouldBe false
    await(client.query(IsEmpty(Arr()))).to[Boolean].get shouldBe true

    await(client.query(IsNonEmpty(Arr(1, 2, 3)))).to[Boolean].get shouldBe true
    await(client.query(IsNonEmpty(Arr()))).to[Boolean].get shouldBe false

    //pages
    await(client.query(IsEmpty(Paginate(Match(Index("spells_by_element"), randomElement))))).to[Boolean].get shouldBe false
    await(client.query(IsEmpty(Paginate(Match(Index("spells_by_element"), "an invalid element"))))).to[Boolean].get shouldBe true

    await(client.query(IsNonEmpty(Paginate(Match(Index("spells_by_element"), randomElement))))).to[Boolean].get shouldBe true
    await(client.query(IsNonEmpty(Paginate(Match(Index("spells_by_element"), "an invalid element"))))).to[Boolean].get shouldBe false
  }

  it should "test resource modification" in {
    val createF = client.query(Create(Collection("spells"), Obj("data" -> Obj("name" -> "Magic Missile", "element" -> "arcane", "cost" -> 10L))))
    val createR = await(createF)
    createR(RefField).get.collection shouldBe Some(RefV("spells", Native.Collections))
    createR(RefField).get.database shouldBe None
    createR("data", "name").to[String].get shouldBe "Magic Missile"
    createR("data", "element").to[String].get shouldBe "arcane"
    createR("data", "cost").to[Long].get shouldBe 10L

    val updateF = client.query(Update(createR(RefField), Obj("data" -> Obj("name" -> "Faerie Fire", "cost" -> NullV))))
    val updateR = await(updateF)
    updateR(RefField).get shouldBe createR(RefField).get
    updateR("data", "name").to[String].get shouldBe "Faerie Fire"
    updateR("data", "element").to[String].get shouldBe "arcane"
    updateR("data", "cost").isDefined should equal (false)

    val replaceF = client.query(Replace(createR("ref"), Obj("data" -> Obj("name" -> "Volcano", "element" -> Arr("fire", "earth"), "cost" -> 10L))))
    val replaceR = await(replaceF)
    replaceR("ref").get shouldBe createR("ref").get
    replaceR("data", "name").to[String].get shouldBe "Volcano"
    replaceR("data", "element").to[Seq[String]].get shouldBe Seq("fire", "earth")
    replaceR("data", "cost").to[Long].get shouldBe 10L

    val insertF = client.query(Insert(createR("ref"), 1L, Action.Create, Obj("data" -> Obj("cooldown" -> 5L))))
    val insertR = await(insertF)
    insertR("document").get shouldBe createR("ref").get

    val removeF = client.query(Remove(createR("ref"), 2L, Action.Delete))
    val removeR = await(removeF)
    removeR shouldBe NullV

    val deleteF = client.query(Delete(createR("ref")))
    await(deleteF)
    val getF = client.query(Get(createR("ref")))
    intercept[NotFoundException] {
      await(getF)
    }
  }

  it should "test sets" in {
    val create1F = client.query(Create(Collection("spells"),
      Obj("data" -> Obj("name" -> "Magic Missile", "element" -> "arcane", "cost" -> 10L))))
    val create2F = client.query(Create(Collection("spells"),
      Obj("data" -> Obj("name" -> "Fireball", "element" -> "fire", "cost" -> 10L))))
    val create3F = client.query(Create(Collection("spells"),
      Obj("data" -> Obj("name" -> "Faerie Fire", "element" -> Arr("arcane", "nature"), "cost" -> 10L))))
    val create4F = client.query(Create(Collection("spells"),
      Obj("data" -> Obj("name" -> "Summon Animal Companion", "element" -> "nature", "cost" -> 10L))))

    val create1R = await(create1F)
    val create2R = await(create2F)
    val create3R = await(create3F)
    val create4R = await(create4F)

    val matchF = client.query(Paginate(Match(Index("spells_by_element"), "arcane")))
    val matchR = await(matchF)
    matchR("data").to[Seq[RefV]].get should contain (create1R("ref").get)

    val matchEventsF = client.query(Paginate(Match(Index("spells_by_element"), "arcane"), events = true))
    val matchEventsR = await(matchEventsF)
    matchEventsR(PageEvents).get map { _.ref } should contain (create1R("ref").to[RefV].get)

    val unionF = client.query(Paginate(Union(
      Match(Index("spells_by_element"), "arcane"),
      Match(Index("spells_by_element"), "fire"))))
    val unionR = await(unionF)
    unionR(PageRefs).get should (contain (create1R(RefField).get) and contain (create2R(RefField).get))

    val unionEventsF = client.query(Paginate(Union(
      Match(Index("spells_by_element"), "arcane"),
      Match(Index("spells_by_element"), "fire")), events = true))
    val unionEventsR = await(unionEventsF)

    unionEventsR(PageEvents).get collect { case e if e.action == "add" => e.ref } should (
      contain (create1R(RefField).get) and contain (create2R(RefField).get))

    val intersectionF = client.query(Paginate(Intersection(
      Match(Index("spells_by_element"), "arcane"),
      Match(Index("spells_by_element"), "nature"))))
    val intersectionR = await(intersectionF)
    intersectionR(PageRefs).get should contain (create3R(RefField).get)

    val differenceF = client.query(Paginate(Difference(
      Match(Index("spells_by_element"), "nature"),
      Match(Index("spells_by_element"), "arcane"))))

    val differenceR = await(differenceF)
    differenceR(PageRefs).get should contain (create4R(RefField).get)
  }

  it should "test events api" in {
    val randomCollectionName = Random.alphanumeric.take(8).mkString
    val randomCollection = await(client.query(CreateCollection(Obj("name" -> randomCollectionName))))

    val data = await(client.query(Create(randomCollection(RefField).get, Obj("data" -> Obj("x" -> 1)))))
    val dataRef = data(RefField).get

    await(client.query(Update(dataRef, Obj("data" -> Obj("x" -> 2)))))
    await(client.query(Delete(dataRef)))

    case class Event(action: String, document: RefV)

    implicit val eventCodec = Codec.Record[Event]

    // Events
    val events = await(client.query(Paginate(Events(dataRef))))(DataField.to[List[Event]]).get

    events.length shouldBe 3
    events(0).action shouldBe "create"
    events(0).document shouldBe dataRef

    events(1).action shouldBe "update"
    events(1).document shouldBe dataRef

    events(2).action shouldBe "delete"
    events(2).document shouldBe dataRef

    // Singleton
    val singletons = await(client.query(Paginate(Events(Singleton(dataRef)))))(DataField.to[List[Event]]).get

    singletons.length shouldBe 2
    singletons(0).action shouldBe "add"
    singletons(0).document shouldBe dataRef

    singletons(1).action shouldBe "remove"
    singletons(1).document shouldBe dataRef
  }

  it should "test string functions" in {
    await(client.query(ContainsStr("ABCDEF","CDE"))).to[Boolean].get shouldBe true
    await(client.query(ContainsStr("ABCDEF","GHI"))).to[Boolean].get shouldBe false

    await(client.query(ContainsStrRegex("ABCDEF","[A-Z]"))).to[Boolean].get shouldBe true
    await(client.query(ContainsStrRegex("123456","[A-Z]"))).to[Boolean].get shouldBe false

    await(client.query(EndsWith("ABCDEF","DEF"))).to[Boolean].get shouldBe true
    await(client.query(EndsWith("ABCDEF","ABC"))).to[Boolean].get shouldBe false

    await(client.query(FindStr("heLLo world","world"))).to[Long].get shouldBe 6L
    await(client.query(Length("heLLo world"))).to[Long].get shouldBe 11L
    await(client.query(LowerCase("hEllO wORLd"))).to[String].get shouldBe "hello world"
    await(client.query(LTrim("   hello world"))).to[String].get shouldBe "hello world"
    await(client.query(RegexEscape("ABCDEF"))).to[String].get shouldBe """\QABCDEF\E"""
    await(client.query(ReplaceStrRegex("hello world","hello","bye"))).to[String].get shouldBe "bye world"
    await(client.query(Repeat("bye "))).to[String].get shouldBe "bye bye "
    await(client.query(Repeat("bye ",3))).to[String].get shouldBe "bye bye bye "
    await(client.query(ReplaceStr("hello world","hello","bye"))).to[String].get shouldBe "bye world"
    await(client.query(RTrim("hello world    "))).to[String].get shouldBe "hello world"
    await(client.query(Space(4))).to[String].get shouldBe "    "

    await(client.query(StartsWith("ABCDEF","ABC"))).to[Boolean].get shouldBe true
    await(client.query(StartsWith("ABCDEF","DEF"))).to[Boolean].get shouldBe false

    await(client.query(SubString("heLLo world", 6))).to[String].get shouldBe "world"
    await(client.query(Trim("    hello world    "))).to[String].get shouldBe "hello world"
    await(client.query(TitleCase("heLLo worlD"))).to[String].get shouldBe "Hello World"
    await(client.query(UpperCase("hello world"))).to[String].get shouldBe "HELLO WORLD"

    await(client.query(Casefold("Hen Wen"))).to[String].get shouldBe "hen wen"

    // https://unicode.org/reports/tr15/
    await(client.query(Casefold("\u212B", Normalizer.NFD))).to[String].get shouldBe "A\u030A"
    await(client.query(Casefold("\u212B", Normalizer.NFC))).to[String].get shouldBe "\u00C5"
    await(client.query(Casefold("\u1E9B\u0323", Normalizer.NFKD))).to[String].get shouldBe "\u0073\u0323\u0307"
    await(client.query(Casefold("\u1E9B\u0323", Normalizer.NFKC))).to[String].get shouldBe "\u1E69"
    await(client.query(Casefold("\u212B", Normalizer.NFKCCaseFold))).to[String].get shouldBe "\u00E5"

    await(client.query(NGram("what"))).to[Seq[String]].get shouldBe Seq("w", "wh", "h", "ha", "a", "at", "t")
    await(client.query(NGram("what", 2, 3))).to[Seq[String]].get shouldBe Seq("wh", "wha", "ha", "hat", "at")

    await(client.query(NGram(Arr("john", "doe")))).to[Seq[String]].get shouldBe Seq("j", "jo", "o", "oh", "h", "hn", "n", "d", "do", "o", "oe", "e")
    await(client.query(NGram(Arr("john", "doe"), 3, 4))).to[Seq[String]].get shouldBe Seq("joh", "john", "ohn", "doe")

    await(client.query(Format("%3$s%1$s %2$s", "DB", "rocks", "Fauna"))).to[String].get shouldBe "FaunaDB rocks"
  }

  it should "test math functions" in {

    val absF = client.query(Abs(-100L))
    val absR = await(absF).to[Long].get
    absR shouldBe 100L

    val acosF = client.query(Trunc(Acos(0.5D), 2))
    val acosR = await(acosF).to[Double].get
    acosR shouldBe 1.04D

    val addF = client.query(Add(100L, 10L))
    val addR = await(addF).to[Long].get
    addR shouldBe 110L

    val asinF = client.query(Trunc(Asin(0.5D), 2))
    val asinR = await(asinF).to[Double].get
    asinR shouldBe 0.52D

    val atanF = client.query(Trunc(Atan(0.5D), 2))
    val atanR = await(atanF).to[Double].get
    atanR shouldBe 0.46D

    val bitandF = client.query(BitAnd(15L, 7L, 3L))
    val bitandR = await(bitandF).to[Long].get
    bitandR shouldBe 3L

    val bitnotF = client.query(BitNot(3L))
    val bitnotR = await(bitnotF).to[Long].get
    bitnotR shouldBe -4L

    val bitorF = client.query(BitOr(15L, 7L, 3L))
    val bitorR = await(bitorF).to[Long].get
    bitorR shouldBe 15L

    val bitxorF = client.query(BitXor(2L, 1L))
    val bitxorR = await(bitxorF).to[Long].get
    bitxorR shouldBe 3L

    val ceilF = client.query(Ceil(1.01D))
    val ceilR = await(ceilF).to[Double].get
    ceilR shouldBe 2.0D

    val cosF = client.query(Trunc(Cos(0.5D), 2))
    val cosR = await(cosF).to[Double].get
    cosR shouldBe 0.87D

    val coshF = client.query(Trunc(Cosh(2L),2))
    val coshR = await(coshF).to[Double].get
    coshR shouldBe 3.76D

    val degreesF = client.query(Trunc(Degrees(2.0D),2))
    val degreesR = await(degreesF).to[Double].get
    degreesR shouldBe 114.59D

    val divideF = client.query(Divide(100L, 10L))
    val divideR = await(divideF).to[Long].get
    divideR shouldBe 10L

    val expF = client.query(Trunc(Exp(2L), 2))
    val expR = await(expF).to[Double].get
    expR shouldBe 7.38D

    val floorF = client.query(Floor(1.91D))
    val floorR = await(floorF).to[Double].get
    floorR shouldBe 1.0D

    val hypotF = client.query(Hypot(3D, 4D))
    val  hypotR = await(hypotF).to[Double].get
    hypotR shouldBe 5.0D

    val lnF = client.query(Trunc(Ln(2L),2))
    val lnR = await(lnF).to[Double].get
    lnR shouldBe 0.69D

    val logF = client.query(Trunc(Log(2L),2))
    val logR = await(logF).to[Double].get
    logR shouldBe 0.30D

    val maxF = client.query(Max(101L, 10L, 1L))
    val maxR = await(maxF).to[Long].get
    maxR shouldBe 101L

    val minF = client.query(Min(101L, 10L))
    val minR = await(minF).to[Long].get
    minR shouldBe 10L

    val moduloF = client.query(Modulo(101L, 10L))
    val moduloR = await(moduloF).to[Long].get
    moduloR shouldBe 1L

    val multiplyF = client.query(Multiply(100L, 10L))
    val multiplyR = await(multiplyF).to[Long].get
    multiplyR shouldBe 1000L

    val radiansF = client.query(Trunc(Radians(500), 2))
    val radiansR = await(radiansF).to[Double].get
    radiansR shouldBe 8.72D

    val roundF = client.query(Round(12345.6789))
    val roundR = await(roundF).to[Double].get
    roundR shouldBe 12345.68D

    val signF = client.query(Sign(3L))
    val signR = await(signF).to[Long].get
    signR shouldBe 1L

    val sinF = client.query(Trunc(Sin(0.5D), 2))
    val sinR = await(sinF).to[Double].get
    sinR shouldBe 0.47D

    val sinhF = client.query(Trunc(Sinh(0.5D), 2))
    val sinhR = await(sinhF).to[Double].get
    sinhR shouldBe 0.52D

    val sqrtF = client.query(Sqrt(16L))
    val sqrtR = await(sqrtF).to[Double].get
    sqrtR shouldBe 4L

    val subtractF = client.query(Subtract(100L, 10L))
    val subtractR = await(subtractF).to[Long].get
    subtractR shouldBe 90L

    val tanF = client.query(Trunc(Tan(0.5D), 2))
    val tanR = await(tanF).to[Double].get
    tanR shouldBe 0.54D

    val tanhF = client.query(Trunc(Tanh(0.5D), 2))
    val tanhR = await(tanhF).to[Double].get
    tanhR shouldBe 0.46D

    val truncF = client.query(Trunc(123.456D, 2L))
    val truncR = await(truncF).to[Double].get
    truncR shouldBe 123.45D

  }

  it should "test miscellaneous functions" in {
    val newIdF = client.query(NewId())
    val newIdR = await(newIdF).to[String].get
    newIdR should not be null

    val equalsF = client.query(Equals("fire", "fire"))
    val equalsR = await(equalsF).to[Boolean].get
    equalsR shouldBe true

    val concatF = client.query(Concat(Arr("Magic", "Missile")))
    val concatR = await(concatF).to[String].get
    concatR shouldBe "MagicMissile"

    val concat2F = client.query(Concat(Arr("Magic", "Missile"), " "))
    val concat2R = await(concat2F).to[String].get
    concat2R shouldBe "Magic Missile"

    val containsF = client.query(Contains("favorites" / "foods", Obj("favorites" -> Obj("foods" -> Arr("crunchings", "munchings")))))
    val containsR = await(containsF).to[Boolean].get
    containsR shouldBe true

    await(client.query(Contains("field", Obj("field" -> "value")))) shouldBe TrueV
    await(client.query(Contains(1, Arr("value0", "value1", "value2")))) shouldBe TrueV

    val selectF = client.query(Select("favorites" / "foods" / 1, Obj("favorites" -> Obj("foods" -> Arr("crunchings", "munchings", "lunchings")))))
    val selectR = await(selectF).to[String].get
    selectR shouldBe "munchings"

    await(client.query(Select("field", Obj("field" -> "value")))) shouldBe StringV("value")
    await(client.query(Select("non-existent-field", Obj("field" -> "value"), "a default value"))) shouldBe StringV("a default value")

    await(client.query(Select(1, Arr("value0", "value1", "value2")))) shouldBe StringV("value1")
    await(client.query(Select(100, Arr("value0", "value1", "value2"), "a default value"))) shouldBe StringV("a default value")

    await(client.query(Contains("a" / "nested" / 0 / "path", Obj("a" -> Obj("nested" -> Arr(Obj("path" -> "value"))))))) shouldBe TrueV
    await(client.query(Select("a" / "nested" / 0 / "path", Obj("a" -> Obj("nested" -> Arr(Obj("path" -> "value"))))))) shouldBe StringV("value")

    await(client.query(SelectAll("foo", Arr(Obj("foo" -> "bar"), Obj("foo" -> "baz"), Obj("a" -> "b"))))) shouldBe ArrayV("bar", "baz")
    await(client.query(SelectAll("foo" / "bar", Arr(Obj("foo" -> Obj("bar" -> 1)), Obj("foo" -> Obj("bar" -> 2)))))) shouldBe ArrayV(1, 2)
    await(client.query(SelectAll("foo" / 0, Arr(Obj("foo" -> Arr(0, 1)), Obj("foo" -> Arr(2, 3)))))) shouldBe ArrayV(0, 2)

    val andF = client.query(And(true, false))
    val andR = await(andF).to[Boolean].get
    andR shouldBe false

    val orF = client.query(Or(true, false))
    val orR = await(orF).to[Boolean].get
    orR shouldBe true

    val notF = client.query(Not(false))
    val notR = await(notF).to[Boolean].get
    notR shouldBe true
  }

  it should "test conversion functions" in {
    val strF = client.query(ToString(100L))
    val strR = await(strF).to[String].get
    strR should equal ("100")

    val numF = client.query(ToNumber("100"))
    val numR = await(numF).to[Long].get
    numR should equal (100L)
  }

  it should "test time functions" in {
    import java.util.Calendar._

    val timeF = client.query(ToTime("1970-01-01T00:00:00Z"))
    val timeR = await(timeF).to[TimeV].get.toInstant
    timeR should equal (Instant.ofEpochMilli(0))

    val dateF = client.query(ToDate("1970-01-01"))
    val dateR = await(dateF).to[DateV].get.localDate
    dateR should equal (LocalDate.ofEpochDay(0))

    val cal = java.util.Calendar.getInstance()
    val nowStr = Time(cal.toInstant.toString)

    val toSecondsF = client.query(ToSeconds(Epoch(0, TimeUnit.Second)))
    val secondsR = await(toSecondsF).to[Long].get
    secondsR should equal (0)

    val toMillisF = client.query(ToMillis(nowStr))
    val toMillisR = await(toMillisF).to[Long].get
    toMillisR should equal (cal.getTimeInMillis)

    val toMicrosF = client.query(ToMicros(Epoch(1552733214259L, TimeUnit.Second)))
    val toMicrosR = await(toMicrosF).to[Long].get
    toMicrosR should equal (1552733214259000000L)

    val dayOfYearF = client.query(DayOfYear(nowStr))
    val dayOfYearR = await(dayOfYearF).to[Long].get
    dayOfYearR should equal (cal.get(DAY_OF_YEAR))

    val dayOfMonthF = client.query(DayOfMonth(nowStr))
    val dayOfMonthR = await(dayOfMonthF).to[Long].get
    dayOfMonthR should equal (cal.get(DAY_OF_MONTH))

    val dayOfWeekF = client.query(DayOfWeek(nowStr))
    val dayOfWeekR = await(dayOfWeekF).to[Long].get
    dayOfWeekR should equal (cal.get(DAY_OF_WEEK)-1)

    val yearF = client.query(Year(nowStr))
    val yearR = await(yearF).to[Long].get
    yearR should equal (cal.get(YEAR))

    val monthF = client.query(Month(nowStr))
    val monthR = await(monthF).to[Long].get
    monthR should equal (cal.get(MONTH)+1)

    val hourF = client.query(Hour(Epoch(0, TimeUnit.Second)))
    val hourR = await(hourF).to[Long].get
    hourR should equal (0)

    val minuteF = client.query(Minute(nowStr))
    val minuteR = await(minuteF).to[Long].get
    minuteR should equal (cal.get(MINUTE))

    val secondF = client.query(query.Second(nowStr))
    val secondR = await(secondF).to[Long].get
    secondR should equal (cal.get(SECOND))
  }

  it should "test date and time functions" in {
    val timeF = client.query(Time("1970-01-01T00:00:00-04:00"))
    val timeR = await(timeF)
    timeR.to[TimeV].get.toInstant shouldBe Instant.ofEpochMilli(0).plus(4, ChronoUnit.HOURS)
    timeR.to[Instant].get shouldBe Instant.ofEpochMilli(0).plus(4, ChronoUnit.HOURS)

    val epochR = await(client.query(Arr(
      Epoch(2, TimeUnit.Day),
      Epoch(1, TimeUnit.HalfDay),
      Epoch(12, TimeUnit.Hour),
      Epoch(30, TimeUnit.Minute),
      Epoch(30, TimeUnit.Second),
      Epoch(10, TimeUnit.Millisecond),
      Epoch(42, TimeUnit.Nanosecond),
      Epoch(40, TimeUnit.Microsecond)
    )))

    epochR.collect(Field.to[Instant]).get.sorted shouldBe Seq(
      Instant.ofEpochMilli(0).plus(42, ChronoUnit.NANOS),
      Instant.ofEpochMilli(0).plus(40, ChronoUnit.MICROS),
      Instant.ofEpochMilli(0).plus(10, ChronoUnit.MILLIS),
      Instant.ofEpochMilli(0).plus(30, ChronoUnit.SECONDS),
      Instant.ofEpochMilli(0).plus(30, ChronoUnit.MINUTES),
      Instant.ofEpochMilli(0).plus(12, ChronoUnit.HOURS),
      Instant.ofEpochMilli(0).plus(1, ChronoUnit.HALF_DAYS),
      Instant.ofEpochMilli(0).plus(2, ChronoUnit.DAYS)
    )

    epochR(0).to[TimeV].get.toInstant shouldBe Instant.ofEpochMilli(0).plus(2, ChronoUnit.DAYS)
    epochR(0).to[Instant].get shouldBe Instant.ofEpochMilli(0).plus(2, ChronoUnit.DAYS)

    val dateF = client.query(query.Date("1970-01-02"))
    val dateR = await(dateF)
    dateR.to[DateV].get.localDate shouldBe LocalDate.ofEpochDay(1)
    dateR.to[LocalDate].get shouldBe LocalDate.ofEpochDay(1)
  }

  it should "test date and time match functions" in {
    val addTimeF = client.query(TimeAdd(Epoch(0, TimeUnit.Second), 1, TimeUnit.Day))
    val addTimeR = await(addTimeF)
    addTimeR.to[Instant].get shouldBe Instant.ofEpochMilli(0).plus(1, ChronoUnit.DAYS)

    val addDateF = client.query(TimeAdd(Date("1970-01-01"), 1, TimeUnit.Day))
    val addDateR = await(addDateF)
    addDateR.to[LocalDate].get shouldBe LocalDate.ofEpochDay(1)

    val subTimeF = client.query(TimeSubtract(Epoch(0, TimeUnit.Second), 1, TimeUnit.Day))
    val subTimeR = await(subTimeF)
    subTimeR.to[Instant].get shouldBe Instant.ofEpochMilli(0).minus(1, ChronoUnit.DAYS)

    val subDateF = client.query(TimeSubtract(Date("1970-01-01"), 1, TimeUnit.Day))
    val subDateR = await(subDateF)
    subDateR.to[LocalDate].get shouldBe LocalDate.ofEpochDay(-1)

    val diffTimeF = client.query(TimeDiff(Epoch(0, TimeUnit.Second), Epoch(1, TimeUnit.Second), TimeUnit.Second))
    val diffTimeR = await(diffTimeF)
    diffTimeR.to[Long].get should equal (1)

    val diffDateF = client.query(TimeDiff(Date("1970-01-01"), Date("1970-01-02"), TimeUnit.Day))
    val diffDateR = await(diffDateF)
    diffDateR.to[Long].get should equal (1)
  }

   it should "test authentication functions" in {
      val createF = client.query(Create(Collection("spells"), Obj("credentials" -> Obj("password" -> "abcdefg"))))
      val createR = await(createF)

      // Login
      val loginF = client.query(Login(createR(RefField), Obj("password" -> "abcdefg")))
      val secret = await(loginF)(SecretField).get

      // HasIdentity
      val hasIdentity = client.sessionWith(secret)(_.query(HasIdentity()))
      await(hasIdentity).to[Boolean].get shouldBe true

      // Identity
      val identity = client.sessionWith(secret)(_.query(Identity()))
      await(identity).to[RefV].get shouldBe createR(RefField).get

      // Logout
      val loggedOut = client.sessionWith(secret)(_.query(Logout(false)))
      await(loggedOut).to[Boolean].get shouldBe true

      // Identify
      val identifyF = client.query(Identify(createR(RefField), "abcdefg"))
      val identifyR = await(identifyF)
      identifyR.to[Boolean].get shouldBe true
      }

  it should "create session client" in {
    val otherClient = client.sessionClient(config("root_token"))

    try
      await(otherClient.query("echo string")).to[String].get shouldBe "echo string"
    finally
      otherClient.close()
  }

  it should "should not create session clients on a closed client" in {
    val newClient = FaunaClient(endpoint = config("root_url"), secret = config("root_token"))

    newClient.close()

    a[IllegalStateException] should be thrownBy newClient.sessionClient("sekret")
  }

  it should "find key by secret" in {
    val key = await(rootClient.query(CreateKey(Obj("database" -> Database(testDbName), "role" -> "admin"))))

    await(rootClient.query(KeyFromSecret(key(SecretField).get))) should equal(await(rootClient.query(Get(key(RefField).get))))
  }

  it should "create a function" in {
    val query = Query((a, b) => Concat(Arr(a, b), "/"))

    await(client.query(CreateFunction(Obj("name" -> "a_function", "body" -> query))))

    await(client.query(Exists(Function("a_function")))).to[Boolean].get shouldBe true
  }

  it should "call a function" in  {
    val query = Query((a, b) => Concat(Arr(a, b), "/"))

    await(client.query(CreateFunction(Obj("name" -> "concat_with_slash", "body" -> query))))

    await(client.query(Call(Function("concat_with_slash"), "a", "b"))).to[String].get shouldBe "a/b"
  }

  it should "parse function errors" in {
    val err = the[BadRequestException] thrownBy {
      await(client.query(
        CreateFunction(Obj(
          "name" -> "function_with_abort",
          "body" -> Query(Lambda("_", Abort("this function failed")))
        ))
      ))
      await(client.query(Call(Function("function_with_abort"))))
    }

    err.getMessage should include(
      "call error: Calling the function resulted in an error.")

    val cause = err.errors.head.cause.head
    cause.code shouldEqual "transaction aborted"
    cause.description shouldEqual "this function failed"
  }

  it should "create a role" in {
    val name = s"a_role_${Random.alphanumeric.take(8).mkString}"

    await(rootClient.query(CreateRole(Obj(
      "name" -> name,
      "privileges" -> Obj(
        "resource" -> Collections(),
        "actions" -> Obj("read" -> true)
      )
    ))))

    await(rootClient.query(Exists(Role(name)))).to[Boolean].get shouldBe true
  }

  it should "read a role from a nested database" in {
    val childCli = createNewDatabase(adminClient, "db-for-roles")

    await(childCli.query(CreateRole(Obj(
      "name" -> "a_role",
      "privileges" -> Obj(
        "resource" -> Collections(),
        "actions" -> Obj("read" -> true)
      )
    ))))

    await(childCli.query(Paginate(Roles())))(PageRefs).get shouldBe Seq(RefV("a_role", Native.Roles))
    await(adminClient.query(Paginate(Roles(Database("db-for-roles")))))(PageRefs).get shouldBe
      Seq(RefV("a_role", Native.Roles, RefV("db-for-roles", Native.Databases)))
  }

  it should "move database" in {
    val db1Name = Random.alphanumeric.take(10).mkString
    val db2Name = Random.alphanumeric.take(10).mkString
    val clsName = Random.alphanumeric.take(10).mkString

    val db1 = createNewDatabase(adminClient, db1Name)
    val db2 = createNewDatabase(adminClient, db2Name)
    await(db2.query(CreateCollection(Obj("name" -> clsName))))

    await(db1.query(Paginate(Databases())))(PageRefs).get shouldBe empty

    await(adminClient.query(MoveDatabase(Database(db2Name), Database(db1Name))))

    await(db1.query(Paginate(Databases())))(PageRefs).get shouldBe List(RefV(db2Name, Native.Databases))
    await(db1.query(Paginate(Collections(Database(db2Name)))))(PageRefs).get shouldBe List(RefV(clsName, Native.Collections, RefV(db2Name, Native.Databases)))
  }

  case class Spell(name: String, element: Either[String, Seq[String]], cost: Option[Long])

  implicit val spellCodec: Codec[Spell] = Codec.caseClass[Spell]

  it should "encode/decode user classes" in {
    val masterSummon = Spell("Master Summon", Left("wind"), None)
    val magicMissile = Spell("Magic Missile", Left("arcane"), Some(10))
    val faerieFire = Spell("Faerie Fire", Right(Seq("arcane", "nature")), Some(10))

    val masterSummonCreated = await(client.query(Create(Collection("spells"), Obj("data" -> masterSummon))))
    masterSummonCreated("data").to[Spell].get shouldBe masterSummon

    val spells = await(client.query(Map(Paginate(Match(Index("spells_by_element"), "arcane")), Lambda(x => Select("data", Get(x))))))("data").get
    spells.to[Set[Spell]].get shouldBe Set(magicMissile, faerieFire)
  }

  it should "create collection in a nested database" in {
    val client1 = createNewDatabase(adminClient, "parent-database")
    createNewDatabase(client1, "child-database")

    val key = await(client1.query(CreateKey(Obj("database" -> Database("child-database"), "role" -> "server"))))

    val client2 = FaunaClient(secret = key(SecretField).get, endpoint = config("root_url"))

    await(client2.query(CreateCollection(Obj("name" -> "a_collection"))))

    val nestedDatabase = Database("child-database", Database("parent-database"))

    await(client.query(Exists(Collection("a_collection", nestedDatabase)))).to[Boolean].get shouldBe true

    val allCollections = await(client.query(Paginate(Collections(nestedDatabase))))("data")
    allCollections.to[List[RefV]].get shouldBe List(RefV("a_collection", Native.Collections, RefV("child-database", Native.Databases, RefV("parent-database", Native.Databases))))
  }

  it should "test for keys in nested database" in {
    val client = createNewDatabase(adminClient, "db-for-keys")

    await(client.query(CreateDatabase(Obj("name" -> "db-test"))))

    val serverKey = await(client.query(CreateKey(Obj("database" -> Database("db-test"), "role" -> "server"))))("ref").get
    val adminKey = await(client.query(CreateKey(Obj("database" -> Database("db-test"), "role" -> "admin"))))("ref").get

    await(client.query(Paginate(Keys())))("data").to[List[Value]].get shouldBe List(serverKey, adminKey)

    await(adminClient.query(Paginate(Keys(Database("db-for-keys")))))("data").to[List[Value]].get shouldBe List(serverKey, adminKey)
  }

  it should "create recursive refs from string" in {
    await(client.query(Ref("collections/widget/123"))) shouldBe RefV("123", RefV("widget", Native.Collections))
  }

  it should "not break do with one element" in {
    await(client.query(Do(1))).to[Long].get shouldBe 1
    await(client.query(Do(1, 2))).to[Long].get shouldBe 2
    await(client.query(Do(Arr(1, 2)))).to[Array[Long]].get shouldBe Array(1L, 2L)
  }

  it should "parse complex index" in {
    await(client.query(CreateClass(Obj("name" -> "reservations"))))

    val indexF = client.query(CreateIndex(Obj(
      "name" -> "reservations_by_lastName",
      "source" -> Obj(
        "class" -> Class("reservations"),
        "fields" -> Obj(
          "cfLastName" -> Query(Lambda(x => Casefold(Select(Path("data", "guestInfo", "lastName"), x)))),
          "fActive" -> Query(Lambda(x => Select(Path("data", "active"), x)))
        )
      ),
      "terms" -> Arr(Obj("binding" -> "cfLastName"), Obj("binding" -> "fActive")),
      "values" -> Arr(
        Obj("field" -> Arr("data", "checkIn")),
        Obj("field" -> Arr("data", "checkOut")),
        Obj("field" -> Arr("ref"))
      ),
      "active" -> true
    )))

    val index = await(indexF)
    index("name").get shouldBe StringV("reservations_by_lastName")
  }

  it should "merge objects" in {
    //empty object
    await(client.query(
      Merge(
        Obj(),
        Obj("x" -> 10, "y" -> 20)
      )
    )) shouldBe ObjectV("x" -> 10, "y" -> 20)

    //adds field
    await(client.query(
      Merge(
        Obj("x" -> 10, "y" -> 20),
        Obj("z" -> 30)
      )
    )) shouldBe ObjectV("x" -> 10, "y" -> 20, "z" -> 30)

    //replace field
    await(client.query(
      Merge(
        Obj("x" -> 10, "y" -> 20, "z" -> -1),
        Obj("z" -> 30)
      )
    )) shouldBe ObjectV("x" -> 10, "y" -> 20, "z" -> 30)

    //remove field
    await(client.query(
      Merge(
        Obj("x" -> 10, "y" -> 20, "z" -> -1),
        Obj("z" -> Null())
      )
    )) shouldBe ObjectV("x" -> 10, "y" -> 20)

    //merge multiple objects
    await(client.query(
      Merge(
        Obj(),
        Arr(
          Obj("x" -> 10),
          Obj("y" -> 20),
          Obj("z" -> 30)
        )
      )
    )) shouldBe ObjectV("x" -> 10, "y" -> 20, "z" -> 30)

    //ignore left value
    await(client.query(
      Merge(
        Obj("x" -> 10, "y" -> 20),
        Obj("x" -> 100, "y" -> 200),
        Lambda((key, left, right) => right)
      )
    )) shouldBe ObjectV("x" -> 100, "y" -> 200)

    //ignore right value
    await(client.query(
      Merge(
        Obj("x" -> 10, "y" -> 20),
        Obj("x" -> 100, "y" -> 200),
        Lambda((key, left, right) => left)
      )
    )) shouldBe ObjectV("x" -> 10, "y" -> 20)

    //lambda 1-arity -> return [key, leftValue, rightValue]
    await(client.query(
      Merge(
        Obj("x" -> 10, "y" -> 20),
        Obj("x" -> 100, "y" -> 200),
        Lambda(value => value)
      )
    )) shouldBe ObjectV("x" -> ArrayV("x", 10, 100), "y" -> ArrayV("y", 20, 200))
  }

  it should "reduce collections" in {
    val collName = Random.alphanumeric.take(8).mkString
    val indexName = Random.alphanumeric.take(8).mkString

    val values = Arr((1 to 100).map(i => i: Expr): _*)

    await(client.query(CreateCollection(Obj("name" -> collName))))
    await(client.query(CreateIndex(Obj(
      "name" -> indexName,
      "source" -> Collection(collName),
      "active" -> true,
       "values" -> Arr(
         Obj("field" -> Arr("data", "value")),
         Obj("field" -> Arr("data", "foo"))
       )
    ))))

    await(client.query(
      Foreach(
        values,
        Lambda(i => Create(Collection(collName), Obj(
          "data" -> Obj("value" -> i, "foo" -> "bar")
        )))
      )
    ))

    //array
    await(client.query(
      Reduce(
        Lambda((acc, i) => Add(acc, i)),
        10,
        values
      )
    )).to[Long].get shouldBe 5060L

    //page
    await(client.query(
      Reduce(
        Lambda((acc, i) => Add(acc, Select(0, i))),
        10,
        Paginate(Match(Index(indexName)), size = 100)
      )
    ))("data").to[Seq[Long]].get shouldBe Seq(5060L)

    //set
    await(client.query(
      Reduce(
        Lambda((acc, i) => Add(acc, Select(0, i))),
        10,
        Match(Index(indexName))
      )
    )).to[Long].get shouldBe 5060L
  }

  it should "count/sum/mean collections" in {
    val collName = Random.alphanumeric.take(8).mkString
    val indexName = Random.alphanumeric.take(8).mkString

    val values = Arr((1 to 100).map(i => i: Expr): _*)

    await(client.query(CreateCollection(Obj("name" -> collName))))
    await(client.query(CreateIndex(Obj(
      "name" -> indexName,
      "source" -> Collection(collName),
      "active" -> true,
      "values" -> Arr(
        Obj("field" -> Arr("data", "value"))
      )
    ))))

    await(client.query(
      Foreach(
        values,
        Lambda(i => Create(Collection(collName), Obj(
          "data" -> Obj("value" -> i)
        )))
      )
    ))

    //array
    await(client.query(
      Arr(
        Sum(values),
        Count(values),
        Mean(values)
      )
    )) shouldBe ArrayV(5050L, 100L, 50.5d)

    //sets
    await(client.query(
      Arr(
        Sum(Match(Index(indexName))),
        Count(Match(Index(indexName))),
        Mean(Match(Index(indexName)))
      )
    )) shouldBe ArrayV(5050L, 100L, 50.5d)

    //pages
    await(client.query(
      Arr(
        Select(Path("data", 0), Sum(Paginate(Match(Index(indexName)), size = 100))),
        Select(Path("data", 0), Count(Paginate(Match(Index(indexName)), size = 100))),
        Select(Path("data", 0), Mean(Paginate(Match(Index(indexName)), size = 100)))
      )
    )) shouldBe ArrayV(5050L, 100L, 50.5d)
  }

  it should "range" in {
    val col = await(client.query(CreateCollection(Obj("name" -> Random.alphanumeric.take(10).mkString))))

    val index = await(client.query(CreateIndex(Obj(
      "name" -> Random.alphanumeric.take(10).mkString,
      "source" -> col("ref").get,
      "values" -> Arr(
        Obj("field" -> Arr("data", "value")),
        Obj("field" -> "ref"),
      ),
      "active" -> true
    ))))

    await(client.query(Foreach(
      (1 to 20).toList,
      Lambda(i =>
        Create(
          col("ref").get,
          Obj("data" -> Obj("value" -> i))
        )
      )
    )))

    def query(set: Expr) =
      await(client.query(Select("data",
        Map(Paginate(set), Lambda((value, ref) => value))
      ))).to[List[Int]].get

    val m = Match(index("ref").get)
    query(Range(m, 3, 7)) shouldBe (3 to 7).toList
    query(Union(Range(m, 1, 10), Range(m, 11, 20))) shouldBe (1 to 20).toList
    query(Difference(Range(m, 1, 20), Range(m, 11, 20))) shouldBe (1 to 10).toList
    query(Intersection(Range(m, 1, 20), Range(m, 5, 15))) shouldBe (5 to 15).toList
  }

  def createNewDatabase(client: FaunaClient, name: String): FaunaClient = {
    await(client.query(CreateDatabase(Obj("name" -> name))))
    val key = await(client.query(CreateKey(Obj("database" -> Database(name), "role" -> "admin"))))
    FaunaClient(secret = key(SecretField).get, endpoint = config("root_url"))
  }
}
