package app.oneassist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AmountsTest {
    @Test fun parsesDutchAndEnglishFormats() {
        assertEquals(450L, Amounts.parseCents("4,50"))
        assertEquals(450L, Amounts.parseCents("€4.50"))
        assertEquals(123456L, Amounts.parseCents("€ 1.234,56"))
        assertEquals(123456L, Amounts.parseCents("1,234.56"))
        assertEquals(120000L, Amounts.parseCents("1.200"))
        assertEquals(120000L, Amounts.parseCents("1200"))
        assertEquals(-1299L, Amounts.parseCents("-€ 12,99"))
        assertEquals(-1299L, Amounts.parseCents("−12.99"))
        assertEquals(250000L, Amounts.parseCents("+ € 2.500,00"))
        assertEquals(50L, Amounts.parseCents("0,5"))
        assertNull(Amounts.parseCents("abc"))
        assertNull(Amounts.parseCents(""))
    }

    @Test fun formatsDutch() {
        assertEquals("€ 1.234,56", Amounts.format(123456))
        assertEquals("€ -12,99", Amounts.format(-1299))
        assertEquals("€ 0,05", Amounts.format(5))
    }
}

class MerchantNormalizerTest {
    private fun n(s: String) = MerchantNormalizer.normalize(s)

    @Test fun stripsTerminalNoise() {
        assertEquals("Albert Heijn", n("CCV*ALBERT HEIJN 1234 AMSTERDAM NLD"))
        assertEquals("Albert Heijn", n("Albert Heijn 1234"))
        assertEquals("Albert Heijn", n("AH 1234 UTRECHT"))
        assertEquals("Bakkerij Jansen", n("Zettle_*Bakkerij Jansen"))
        assertEquals("Koffiebar De Tuin", n("SumUp *Koffiebar De Tuin"))
        assertEquals("Snackbar Piet", n("BCK*Snackbar Piet Rotterdam"))
        assertEquals("Ziggo", n("Ziggo Services BV"))
        assertEquals("Vattenfall", n("Vattenfall Klantenservice N.V."))
        assertEquals("Netflix", n("NETFLIX.COM"))
        assertEquals("Netflix", n("Netflix International B.V."))
        assertEquals("NS", n("NS GROEP IZ NS REIZIGERS"))
        assertEquals("Uber Eats", n("UBER *EATS"))
        assertEquals("YouTube", n("Google *YouTube Premium"))
        assertEquals("Jumbo", n("Jumbo Den Haag"))
        assertEquals("Albert Heijn", n("BEA, Betaalpas Albert Heijn 1234,PAS123 NR:ABC123, 12.10.24/18:30 AMSTERDAM"))
        assertEquals("bol.com", n("bol.com"))
        assertEquals("Thuisbezorgd", n("Thuisbezorgd.nl via Takeaway.com"))
    }

    @Test fun prettyCase() {
        assertEquals("Kapper Mooi", n("KAPPER MOOI"))
        assertEquals("KPN", n("KPN B.V."))
        assertEquals("Café de Zwaan", n("Café de Zwaan"))
    }

    @Test fun sameMerchantIsTolerant() {
        assertTrue(MerchantNormalizer.sameMerchant("Albert Heijn", "ALBERT HEIJN 1234 AMSTERDAM"))
        assertTrue(MerchantNormalizer.sameMerchant("NETFLIX.COM", "Netflix"))
        assertTrue(MerchantNormalizer.sameMerchant("Bakkerij Jansen", "Zettle_*Bakkerij Jansen Utrecht"))
        assertFalse(MerchantNormalizer.sameMerchant("Jumbo", "Albert Heijn"))
        assertFalse(MerchantNormalizer.sameMerchant("", "Albert Heijn"))
    }

    @Test fun retailIsNotRet() {
        assertEquals("Retail Shop", n("RETAIL SHOP"))
        assertEquals("RET", n("RET ROTTERDAM"))
    }
}

class IngCsvParserTest {
    private val parser = IngCsvParser()

    private val newFormat = """
        "Datum";"Naam / Omschrijving";"Rekening";"Tegenrekening";"Code";"Af Bij";"Bedrag (EUR)";"Mutatiesoort";"Mededelingen";"Saldo na mutatie";"Tag"
        "20241028";"Albert Heijn 1234 AMSTERDAM";"NL11INGB0001234567";"";"BA";"Af";"23,45";"Betaalautomaat";"Pasvolgnr: 001 28-10-2024 18:30 Transactie: A1B2C3 Term: 12345 Apple Pay";"1.234,56";""
        "20241025";"Werkgever B.V.";"NL11INGB0001234567";"NL22ABNA0123456789";"OV";"Bij";"3.150,00";"Overschrijving";"Naam: Werkgever B.V. Omschrijving: Salaris oktober IBAN: NL22ABNA0123456789";"1.258,01";""
        "20241001";"Netflix International B.V.";"NL11INGB0001234567";"NL33RABO0123456789";"IC";"Af";"13,99";"Incasso";"Naam: Netflix International B.V. Machtiging ID: 123";"100,00";""
        "garbage";"x";"";"";"";"Af";"1,00";"";"";"";""
        "20241002";"Jumbo";"NL11INGB0001234567";"";"BA";"??";"5,00";"Betaalautomaat";"";"";""
    """.trimIndent()

    @Test fun parsesNewSemicolonFormat() {
        assertTrue(parser.canParse(newFormat.lineSequence().first()))
        val r = parser.parse(newFormat)
        assertEquals(3, r.transactions.size)
        assertEquals(2, r.skipped.size)
        val ah = r.transactions[0]
        assertEquals(LocalDate.of(2024, 10, 28), ah.date)
        assertEquals(-2345L, ah.amountCents)
        assertEquals("Albert Heijn 1234 AMSTERDAM", ah.counterparty)
        assertEquals(ImportSource.CSV, ah.source)
        assertFalse(ah.peerToPeer)
        val salary = r.transactions[1]
        assertEquals(315000L, salary.amountCents)
        assertTrue(salary.peerToPeer)
        assertTrue(salary.description.contains("Salaris"))
        assertEquals(-1399L, r.transactions[2].amountCents)
    }

    @Test fun parsesOldCommaFormatWithDashedDates() {
        val csv = """
            "Datum","Naam / Omschrijving","Rekening","Tegenrekening","Code","Af Bij","Bedrag (EUR)","MutatieSoort","Mededelingen"
            "28-10-2024","Jumbo Utrecht","NL11INGB0001234567","","BA","Af","4,50","Betaalautomaat","Pasvolgnr 001"
            "29-10-2024","Jan de Vries","NL11INGB0001234567","NL44INGB0009876543","GT","Bij","20,00","Online bankieren","Pizza"
        """.trimIndent()
        val r = parser.parse(csv)
        assertEquals(2, r.transactions.size)
        assertEquals(LocalDate.of(2024, 10, 28), r.transactions[0].date)
        assertEquals(-450L, r.transactions[0].amountCents)
        assertEquals(2000L, r.transactions[1].amountCents)
        assertTrue(r.transactions[1].peerToPeer)
    }

    @Test fun parsesEnglishHeader() {
        val csv = """
            "Date";"Name / Description";"Account";"Counterparty";"Code";"Debit/credit";"Amount (EUR)";"Transaction type";"Notifications";"Resulting balance";"Tag"
            "20241028";"Lidl";"NL11INGB0001234567";"";"BA";"Debit";"12,30";"Payment terminal";"";"";""
        """.trimIndent()
        val r = parser.parse(csv)
        assertEquals(-1230L, r.transactions.single().amountCents)
    }

    @Test fun rejectsOtherHeaders() {
        assertFalse(parser.canParse("Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"))
    }
}

class RevolutCsvParserTest {
    private val csv = """
        Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
        CARD_PAYMENT,Current,2024-10-28 12:30:01,2024-10-29 08:00:00,Albert Heijn,-4.50,0.00,EUR,COMPLETED,100.00
        CARD_PAYMENT,Current,2024-10-28 13:00:00,,Starbucks,-3.20,0.00,EUR,PENDING,
        TOPUP,Current,2024-10-27 09:00:00,2024-10-27 09:00:01,Top-Up by *1234,100.00,0.00,EUR,COMPLETED,104.50
        ATM,Current,2024-10-26 10:00:00,2024-10-26 10:00:00,Cash at ING,-50.00,1.75,EUR,COMPLETED,4.50
        CARD_PAYMENT,Current,2024-10-25 10:00:00,2024-10-25 10:00:00,"Bistro, Utrecht",-1200.00,0.00,EUR,COMPLETED,10.00
        TRANSFER,Current,2024-10-24 10:00:00,2024-10-24 10:00:00,To Jan de Vries,-25.00,0.00,EUR,REVERTED,10.00
    """.trimIndent()

    @Test fun parsesCompletedRowsAndFees() {
        val p = RevolutCsvParser()
        assertTrue(p.canParse(csv.lineSequence().first()))
        val r = p.parse(csv)
        assertEquals(5, r.transactions.size)
        assertEquals(2, r.skipped.size) // pending + reverted
        val ah = r.transactions[0]
        assertEquals(LocalDate.of(2024, 10, 28), ah.date)
        assertEquals(-450L, ah.amountCents)
        assertEquals(AccountSource.REVOLUT, ah.account)
        assertTrue(r.transactions[1].peerToPeer) // top-up
        val fee = r.transactions.single { it.counterparty == "Revolut fee" }
        assertEquals(-175L, fee.amountCents)
        assertEquals(-120000L, r.transactions.single { it.counterparty == "Bistro, Utrecht" }.amountCents)
    }

    @Test fun importerPicksParserByHeader() {
        assertTrue(CsvImporter().parserFor(csv) is RevolutCsvParser)
        assertEquals(1, CsvImporter().parse("a,b,c\n1,2,3").skipped.size)
    }
}

class NotificationParserTest {
    private val ing = IngNotificationParser()
    private val rev = RevolutNotificationParser()
    private val day = LocalDate.of(2024, 10, 28)

    private fun check(p: NotificationParser, title: String, text: String, cents: Long, merchant: String) {
        val t = p.parse(title, text, day)
        requireNotNull(t) { "expected a transaction for '$title' / '$text'" }
        assertEquals("amount for '$text'", cents, t.amountCents)
        assertEquals("merchant for '$text'", merchant, t.counterparty)
        assertEquals(day, t.date)
        assertEquals("", t.description)
        assertEquals(ImportSource.NOTIFICATION, t.source)
    }

    @Test fun englishPayments() {
        check(rev, "Revolut", "Paid €4.50 at Albert Heijn", -450, "Albert Heijn")
        check(rev, "Revolut", "You paid €12.99 to Netflix", -1299, "Netflix")
        check(rev, "Payment", "You spent €1,234.56 at Coolblue.", -123456, "Coolblue")
        check(rev, "Albert Heijn", "€4.50 paid with your Visa card", -450, "Albert Heijn")
        check(rev, "Revolut", "Refund of €12.99 from Bol.com", 1299, "Bol.com")
        check(rev, "Revolut", "You received €50.00 from Jan de Vries", 5000, "Jan de Vries")
    }

    @Test fun dutchPayments() {
        check(ing, "ING", "€4,50 betaald bij Albert Heijn", -450, "Albert Heijn")
        check(ing, "ING", "Je hebt €25,00 betaald aan Jumbo", -2500, "Jumbo")
        check(ing, "ING", "Bijschrijving van €1.200,00 van Werkgever BV", 120000, "Werkgever BV")
        check(ing, "ING", "Afschrijving €12,99 Netflix", -1299, "Netflix")
        check(ing, "Betaling", "€ 4,50 aan Albert Heijn", -450, "Albert Heijn")
        check(ing, "ING", "Er is € 12,99 afgeschreven door Spotify.", -1299, "Spotify")
        check(rev, "Revolut", "Je hebt € 7,80 betaald bij Starbucks met je kaart", -780, "Starbucks")
    }

    @Test fun transfersAreMarked() {
        assertTrue(rev.parse("Revolut", "You received €50.00 from Jan de Vries", day)!!.peerToPeer)
        assertTrue(rev.parse("Revolut", "You sent €20 to Piet", day)!!.peerToPeer)
        assertFalse(rev.parse("Revolut", "Paid €4.50 at Albert Heijn", day)!!.peerToPeer)
        assertFalse(rev.parse("Revolut", "Refund of €12.99 from Bol.com", day)!!.peerToPeer)
    }

    @Test fun nonPaymentsReturnNull() {
        assertNull(ing.parse("ING", "Je bent ingelogd op een nieuw apparaat", day))
        assertNull(ing.parse("ING", "Nieuwe beveiligingscode: 123456", day))
        assertNull(rev.parse("Revolut", "Invite friends and earn €50 each", day))
        assertNull(rev.parse("Revolut", "Get 3 months of Premium for free!", day))
        assertNull(rev.parse("Revolut", "Your payment of €12.00 at Shop was declined", day))
        assertNull(ing.parse("ING", "Je saldo is € 1.234,56", day))
        assertNull(ing.parse("ING", "Jan stuurde je een betaalverzoek van € 10,00", day))
        assertNull(rev.parse("Revolut", "", day))
    }

    @Test fun registryByPackage() {
        assertTrue(BankNotificationParsers.forPackage("com.ing.mobile") is IngNotificationParser)
        assertTrue(BankNotificationParsers.forPackage("com.revolut.revolut") is RevolutNotificationParser)
        assertNull(BankNotificationParsers.forPackage("com.whatsapp"))
    }
}

class ScreenshotParserTest {
    private val p = BankListScreenshotParser()
    private val today = LocalDate.of(2024, 10, 31)

    @Test fun rowLayoutWithDateHeaders() {
        val lines = listOf(
            "9:41", "Af- en bijschrijvingen",
            "Vandaag",
            "Albert Heijn", "Betaalautomaat", "-€ 12,99",
            "Gisteren",
            "Netflix", "Incasso", "-13,99",
            "28 oktober",
            "Werkgever BV", "+ € 2.500,00",
            "Saldo € 1.234,56",
        )
        val r = p.parse(lines, AccountSource.ING, today)
        assertEquals(3, r.transactions.size)
        assertEquals(Triple(today, -1299L, "Albert Heijn"), r.transactions[0].let { Triple(it.date, it.amountCents, it.counterparty) })
        assertEquals(Triple(today.minusDays(1), -1399L, "Netflix"), r.transactions[1].let { Triple(it.date, it.amountCents, it.counterparty) })
        assertEquals(Triple(LocalDate.of(2024, 10, 28), 250000L, "Werkgever BV"), r.transactions[2].let { Triple(it.date, it.amountCents, it.counterparty) })
        assertTrue(r.transactions.all { it.source == ImportSource.SCREENSHOT })
    }

    @Test fun sameLineAndEnglishDates() {
        val lines = listOf("28 Oct", "Starbucks -€4.50", "Oct 27", "Uber Eats −23.10", "Yesterday", "Spotify €10,99")
        val r = p.parse(lines, AccountSource.CREDIT_CARD, today)
        assertEquals(listOf("Starbucks", "Uber Eats", "Spotify"), r.transactions.map { it.counterparty })
        assertEquals(listOf(-450L, -2310L, -1099L), r.transactions.map { it.amountCents })
        assertEquals(listOf(LocalDate.of(2024, 10, 28), LocalDate.of(2024, 10, 27), today.minusDays(1)), r.transactions.map { it.date })
    }

    @Test fun columnLayout() {
        val lines = listOf("Vandaag", "Albert Heijn", "Jumbo", "Lidl", "-€ 10,00", "-€ 20,00", "-€ 30,00")
        val r = p.parse(lines, AccountSource.ING, today)
        assertEquals(listOf("Albert Heijn" to -1000L, "Jumbo" to -2000L, "Lidl" to -3000L), r.transactions.map { it.counterparty to it.amountCents })
    }

    @Test fun dateHeadersWithoutYearNeverInFuture() {
        assertEquals(LocalDate.of(2023, 12, 24), p.parseDateHeader("24 december", today))
        assertEquals(LocalDate.of(2024, 10, 28), p.parseDateHeader("maandag 28 oktober", today))
        assertEquals(LocalDate.of(2024, 3, 5), p.parseDateHeader("5-3-2024", today))
        assertNull(p.parseDateHeader("Albert Heijn", today))
    }
}
