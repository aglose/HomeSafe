package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.assistant.MAX_TOOL_RESULT_CHARS
import com.meticulouscreations.homesafe.finance.FakeFinanceAccess
import com.meticulouscreations.homesafe.finance.FakeServerConnection
import com.meticulouscreations.homesafe.finance.domain.BankTransaction
import com.meticulouscreations.homesafe.finance.domain.BankTransactionQuery
import com.meticulouscreations.homesafe.finance.domain.BankTransactions
import com.meticulouscreations.homesafe.finance.domain.BankTransactionsRepository
import com.meticulouscreations.homesafe.finance.domain.LinkedAccount
import com.meticulouscreations.homesafe.finance.domain.MoneyDirection
import com.meticulouscreations.homesafe.finance.domain.MoneyTotal
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the assistant reads of every card and bank account, and the relay's answer as it comes to the phone. */
class BankTransactionsToolTest {

    private fun transaction(date: String, amount: Double, name: String, description: String? = null, detail: String? = null, account: String = "Capital One Venture 1234", vararg more: Pair<String, String>) =
        BankTransaction(
            date = date,
            amount = amount,
            currency = more.toMap()["currency"] ?: "USD",
            name = name,
            description = description,
            category = detail?.substringBefore('_'),
            categoryDetail = detail,
            confidence = more.toMap()["confidence"],
            pending = false,
            kind = "spend",
            account = account,
            accountType = "credit card",
            channel = more.toMap()["channel"],
            website = more.toMap()["website"],
            city = more.toMap()["city"],
            region = more.toMap()["region"],
            counterparty = more.toMap()["counterparty"],
            note = more.toMap()["note"],
        )

    private val accounts = listOf(
        LinkedAccount("Capital One Venture 1234", "Capital One", "credit", "credit card", 812.4, readsTransactions = true, needsRelink = false, inBudget = true, count = 300, first = "2024-11-01", last = "2026-10-30"),
        LinkedAccount("Target RedCard 3333", "Target", "credit", "credit card", 12.0, readsTransactions = true, needsRelink = true, inBudget = false, count = 4, first = "2026-01-01", last = "2026-02-01"),
        LinkedAccount("Capital One 360 Checking 5555", "Capital One", "depository", "checking", 4000.0, readsTransactions = true, needsRelink = false, inBudget = false, count = 0, first = null, last = null),
        LinkedAccount("Mortgage 9999", "Rocket", "loan", "mortgage", 300_000.0, readsTransactions = false, needsRelink = false, inBudget = false, count = 0, first = null, last = null),
    )

    private fun answer(transactions: List<BankTransaction>, count: Int = transactions.size) = BankTransactions(
        start = "2026-09-01",
        end = "2026-10-31",
        count = count,
        totals = transactions.takeIf { it.isNotEmpty() }?.let { listOf(MoneyTotal("USD", it.filter { t -> t.amount > 0 }.sumOf { t -> t.amount }, -it.filter { t -> t.amount < 0 }.sumOf { t -> t.amount })) }.orEmpty(),
        accounts = accounts,
        transactions = transactions,
    )

    private val access = FakeFinanceAccess()
    private var answered: Result<BankTransactions> = Result.success(answer(emptyList()))
    private val asked = ArrayList<BankTransactionQuery>()
    private val tool = BankTransactionsTool(
        object : BankTransactionsRepository {
            override suspend fun transactions(query: BankTransactionQuery): Result<BankTransactions> {
                asked += query
                return answered
            }
        },
        access,
    )

    private suspend fun ask(vararg input: Pair<String, String>): String = tool.run(buildJsonObject { input.forEach { (key, value) -> put(key, value) } })

    @Test
    fun whatItIsAskedIsWhatTheRelayIsAsked() = runTest {
        ask("from" to "2026-09-01", "to" to "2026-10-31", "search" to "american", "category" to "TRAVEL", "account" to "checking", "direction" to "OUT")
        assertEquals(BankTransactionQuery("2026-09-01", "2026-10-31", "american", "TRAVEL", "checking", MoneyDirection.OUT), asked.single())
    }

    @Test
    fun whatTheBankWroteIsThereToWorkOutAVagueCharge() = runTest {
        answered = Result.success(
            answer(
                listOf(
                    transaction(
                        "2026-09-23",
                        528.8,
                        " AMERICAN ",
                        "AMERICAN AIR0012345\nFORT WORTH TX",
                        "GENERAL_MERCHANDISE_OTHER",
                        more = arrayOf("channel" to "online", "website" to "aa.com", "city" to "Fort Worth", "region" to "TX", "counterparty" to "American Airlines", "confidence" to "LOW", "note" to "flights"),
                    ),
                ),
            ),
        )

        val line = ask().lines().single { it.startsWith("2026-09-23") }

        assertEquals(
            "2026-09-23 | 528.80 | AMERICAN | GENERAL_MERCHANDISE_OTHER | Capital One Venture 1234 | bank says: AMERICAN AIR0012345 FORT WORTH TX | with: American Airlines | online | " +
                "at Fort Worth, TX | aa.com | category unsure | note: flights",
            line,
        )
    }

    @Test
    fun moneyComingInReadsAsNegativeAndTheTotalsAreSaidToIncludeMoneyMoved() = runTest {
        answered = Result.success(answer(listOf(transaction("2026-10-01", -3000.0, "ACME PAYROLL", account = "Capital One 360 Checking 5555"))))

        val text = ask()

        assertTrue("2026-10-01 | -3000.00 | ACME PAYROLL | - | Capital One 360 Checking 5555" in text)
        assertTrue("1, USD 0.00 going out and 3000.00 coming in." in text)
        assertTrue("aren't what was spent or earned" in text)
    }

    @Test
    fun everyAccountSaysWhatBecomesOfIt() = runTest {
        val text = ask()

        assertTrue("Capital One Venture 1234 (Capital One, credit card), balance 812.40, 300 kept from 2024-11-01 to 2026-10-30, in the budget" in text)
        assertTrue("Target RedCard 3333 (Target, credit card), balance 12.00, 4 kept from 2026-01-01 to 2026-02-01, outside the budget, needs signing in to again" in text)
        assertTrue("Capital One 360 Checking 5555 (Capital One, checking), balance 4000.00, none kept yet" in text)
        assertTrue("Mortgage 9999 (Rocket, mortgage), balance 300000.00, its transactions aren't read" in text)
    }

    @Test
    fun whatDoesntFitIsSaidAndTheTotalsStayWhole() {
        val all = List(1_000) { transaction("2026-10-${(28 - it / 40).toString().padStart(2, '0')}", 10.0, "Merchant $it", "A long description the bank wrote of it $it", "FOOD_AND_DRINK_OTHER") }
        val query = BankTransactionQuery()

        val text = bankTransactionsText(query, answer(all, count = 1_400).copy(totals = listOf(MoneyTotal("USD", 14_000.0, 0.0))))

        assertTrue(text.length <= MAX_TOOL_RESULT_CHARS, "${text.length} characters")
        val shown = text.lines().count { it.startsWith("2026-10-") }
        assertTrue(shown in 1 until 1_000)
        assertTrue("1400, USD 14000.00 going out" in text)
        assertTrue("only the newest $shown of 1400 are below. Left out: ${1_400 - shown}" in text)
        assertTrue("USD ${money(14_000.0 - shown * 10.0)} going out" in text)
        assertTrue(text.indexOf("Accounts:") < text.indexOf("Too many to list"), "the accounts survive it")
    }

    @Test
    fun theRelayListingFewerThanMatchedIsSaidEvenWhenAllOfThemFit() {
        val text = bankTransactionsText(BankTransactionQuery(), answer(listOf(transaction("2026-10-05", 1.0, "One")), count = 1_500).copy(totals = listOf(MoneyTotal("USD", 1_500.0, 0.0))))

        assertTrue("only the newest 1 of 1500 are below. Left out: 1499 from 2026-09-01 to 2026-10-05" in text, text)
    }

    @Test
    fun eachCurrencyKeepsItsOwnTotalAndSaysItselfOnEachLine() {
        val text = bankTransactionsText(
            BankTransactionQuery(),
            answer(listOf(transaction("2026-10-05", 200.0, "Hotel", more = arrayOf("currency" to "EUR")), transaction("2026-10-04", 100.0, "Train"))).copy(
                totals = listOf(MoneyTotal("EUR", 200.0, 0.0), MoneyTotal("USD", 100.0, 5.0), MoneyTotal(null, 7.0, 0.0)),
            ),
        )

        assertTrue("EUR 200.00 going out and 0.00 coming in; USD 100.00 going out and 5.00 coming in; no currency given: 7.00 going out" in text, text)
        assertTrue("2026-10-05 | 200.00 EUR | Hotel" in text)
        assertTrue("2026-10-04 | 100.00 USD | Train" in text)
    }

    @Test
    fun anAccountNoLongerLinkedSaysHowFarBackWhatItLeftGoes() {
        val gone = LinkedAccount("Old Card 1111", "", "credit", null, null, readsTransactions = false, needsRelink = false, inBudget = true, count = 9, first = "2024-01-01", last = "2025-01-01", unlinked = true)

        val text = bankTransactionsText(BankTransactionQuery(), answer(emptyList()).copy(accounts = listOf(gone)))

        assertTrue("Old Card 1111 (credit), no longer linked, 9 kept from 2024-01-01 to 2025-01-01, in the budget" in text, text)
    }

    @Test
    fun aListOfAccountsTooLongToFitStillLeavesTheTransactions() {
        val many = List(3_000) { LinkedAccount("An account of the household's $it", "A Bank", "depository", "checking", 1.0, readsTransactions = true, needsRelink = false, inBudget = false, count = 1, first = "2026-01-01", last = "2026-01-01") }

        val text = bankTransactionsText(BankTransactionQuery(), answer(listOf(transaction("2026-10-05", 1.0, "One"))).copy(accounts = many))

        assertTrue(text.length <= MAX_TOOL_RESULT_CHARS, "${text.length} characters")
        assertTrue("2026-10-05 | 1.00 | One" in text)
    }

    @Test
    fun nothingFoundIsSaid() = runTest {
        assertTrue(ask("search" to "nothing").endsWith("Transactions: none."))
        assertTrue("mentioning \"nothing\"" in ask("search" to "nothing"))
    }

    @Test
    fun askingInTheWrongShapeIsAnswered() = runTest {
        assertEquals("from and to are YYYY-MM-DD.", ask("from" to "2026-09"))
        assertEquals("from and to are YYYY-MM-DD.", ask("to" to "today"))
        assertEquals("from is after to.", ask("from" to "2026-09-02", "to" to "2026-09-01"))
        assertEquals("direction is in or out.", ask("direction" to "sideways"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aFailureIsSaid() = runTest {
        answered = Result.failure(IllegalStateException("HTTP 404"))
        assertEquals("Couldn't read the transactions: HTTP 404", ask())
    }

    @Test
    fun nothingComesOutWhileLocked() = runTest {
        answered = Result.success(answer(listOf(transaction("2026-10-01", 1.0, "AMERICAN"))))
        access.goOffline()

        val text = ask()

        assertTrue(text.startsWith("The finance app is locked"), text)
        assertFalse("AMERICAN" in text)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aLockThatComesOnDuringTheReadStillHoldsItBack() = runTest {
        val locking = BankTransactionsTool(
            object : BankTransactionsRepository {
                override suspend fun transactions(query: BankTransactionQuery): Result<BankTransactions> {
                    access.goOffline()
                    return Result.success(answer(listOf(transaction("2026-10-01", 1.0, "AMERICAN"))))
                }
            },
            access,
        )

        val text = locking.run(buildJsonObject { })

        assertTrue(text.startsWith("The finance app is locked"), text)
        assertFalse("AMERICAN" in text)
    }

    // --- Through the relay ---

    private var down = false
    private val requests = ArrayList<Url>()
    private var relayAnswer = HttpStatusCode.OK to """{"start":"2026-09-01","end":"2026-10-31","count":1,"totals":[{"currency":"USD","out":528.8,"in":0}],"unknown_later":1,
        "accounts":[{"key":"Capital One Venture 1234","institution":"Capital One","name":"Venture","mask":"1234","type":"credit","subtype":"credit card","balance":812.4,
          "reads_transactions":true,"needs_relink":false,"in_budget":true,"count":2,"first":"2026-09-23","last":"2026-10-05"}],
        "transactions":[{"date":"2026-09-23","amount":528.8,"currency":"USD","name":"AMERICAN ","description":"AMERICAN AIR001","category":"GENERAL_MERCHANDISE","category_detail":"GENERAL_MERCHANDISE_OTHER",
          "confidence":"LOW","pending":false,"kind":"spend","account":"Capital One Venture 1234","account_type":"credit card","channel":"online","website":null,"city":null,"region":null,
          "counterparty":null,"note":null}]}"""

    private val client = HttpClient(
        MockEngine { request ->
            requests += request.url
            if (down) throw IOException("Unable to resolve host")
            respond(relayAnswer.second, relayAnswer.first, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout)
    }

    private val repository get() = BankTransactionsRepositoryImpl(BudgetRelayApi(client), FakeServerConnection("https://box.ts.net"), access)

    @Test
    fun theRelaysAnswerComesThrough() = runTest {
        val read = repository.transactions(BankTransactionQuery(start = "2026-09-01", end = "2026-10-31", search = "american air", direction = MoneyDirection.OUT)).getOrThrow()

        assertEquals("/finance/transactions", requests.single().encodedPath)
        assertEquals("american air", requests.single().parameters["search"])
        assertEquals("2026-09-01", requests.single().parameters["start"])
        assertEquals("out", requests.single().parameters["direction"])
        assertEquals(null, requests.single().parameters["account"], "what wasn't asked isn't sent")
        assertEquals(listOf(MoneyTotal("USD", 528.8, 0.0)), read.totals)
        assertEquals("USD", read.transactions.single().currency)
        assertEquals("AMERICAN AIR001", read.transactions.single().description)
        assertEquals("GENERAL_MERCHANDISE_OTHER", read.transactions.single().categoryDetail)
        assertEquals("credit card", read.transactions.single().accountType)
        assertTrue(read.accounts.single().inBudget)
        assertEquals("2026-09-23", read.accounts.single().first)
    }

    @Test
    fun aRelayFromBeforeThisFailsRatherThanSayingNothingWasFound() = runTest {
        relayAnswer = HttpStatusCode.NotFound to """{"detail":"Not Found"}"""
        assertTrue(repository.transactions(BankTransactionQuery()).isFailure)
    }

    @Test
    fun nothingIsAskedWhileLockedOrOffline() = runTest {
        access.goOffline()
        assertTrue(repository.transactions(BankTransactionQuery()).isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aServerThatDoesntAnswerIsLookedAtAgain() = runTest {
        down = true
        assertTrue(repository.transactions(BankTransactionQuery()).isFailure)
        assertEquals(1, access.rechecks)
    }
}
