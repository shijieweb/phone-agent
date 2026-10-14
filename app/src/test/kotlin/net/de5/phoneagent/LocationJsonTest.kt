package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC.md section 10 case 5: the one-line location JSON of section 9. */
class LocationJsonTest {

    @Test
    fun matchesTheDocumentedExample() {
        val fix = LocationFix(
            lat = 39.9,
            lon = 116.4,
            acc = 12.0,
            ts = 1760490000L,
            prov = "gps",
            spd = 0.0,
            bear = 0.0
        )
        assertEquals(
            "{\"lat\":39.9,\"lon\":116.4,\"acc\":12.0,\"ts\":1760490000," +
                "\"prov\":\"gps\",\"spd\":0.0,\"bear\":0.0}",
            locationJson(fix)
        )
    }

    @Test
    fun fieldNamesAndOrderAreStable() {
        val json = locationJson(
            LocationFix(1.0, 2.0, 3.0, 4L, "network", 5.0, 6.0)
        )
        assertTrue(json.indexOf("\"lat\"") < json.indexOf("\"lon\""))
        assertTrue(json.indexOf("\"lon\"") < json.indexOf("\"acc\""))
        assertTrue(json.indexOf("\"acc\"") < json.indexOf("\"ts\""))
        assertTrue(json.indexOf("\"ts\"") < json.indexOf("\"prov\""))
        assertTrue(json.indexOf("\"prov\"") < json.indexOf("\"spd\""))
        assertTrue(json.indexOf("\"spd\"") < json.indexOf("\"bear\""))
        assertTrue(json.contains("\"prov\":\"network\""))
        assertTrue(json.contains("\"ts\":4"))
    }

    @Test
    fun coordinatesKeepSixDecimals() {
        val json = locationJson(LocationFix(39.123456789, 116.987654321, 0.0, 0L, "gps", 0.0, 0.0))
        assertTrue(json.contains("\"lat\":39.123457"))
        assertTrue(json.contains("\"lon\":116.987654"))
    }

    @Test
    fun trailingZerosOfCoordinatesAreStripped() {
        val json = locationJson(LocationFix(39.900000, 116.400000, 0.0, 0L, "gps", 0.0, 0.0))
        assertTrue(json.contains("\"lat\":39.9,"))
        assertTrue(json.contains("\"lon\":116.4,"))
    }

    @Test
    fun accuracySpeedAndBearingKeepOneDecimal() {
        val json = locationJson(LocationFix(1.0, 2.0, 12.34, 0L, "gps", 1.26, 359.9))
        assertTrue(json.contains("\"acc\":12.3"))
        assertTrue(json.contains("\"spd\":1.3"))
        assertTrue(json.contains("\"bear\":359.9"))
    }

    @Test
    fun theProviderIsQuotedAndEscaped() {
        val json = locationJson(LocationFix(1.0, 2.0, 0.0, 0L, "fu\"sed", 0.0, 0.0))
        assertTrue(json.contains("\"prov\":\"fu'sed\""))
    }

    @Test
    fun negativeCoordinatesSurvive() {
        val json = locationJson(LocationFix(-33.86785, 151.20732, 0.0, 0L, "gps", 0.0, 0.0))
        assertTrue(json.contains("\"lat\":-33.86785"))
        assertTrue(json.contains("\"lon\":151.20732"))
    }
}
