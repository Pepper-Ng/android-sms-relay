package nl.landvanhorne.smsrelay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCodeExtractorTest {

    @Test
    fun extractsNumericOtpFromSmsBody() {
        assertEquals("482731", SmsCodeExtractor.extract("Je ONS-code is 482731. Gebruik deze binnen 5 minuten."))
    }

    @Test
    fun returnsNullWhenNoOtpIsPresent() {
        assertNull(SmsCodeExtractor.extract("Welkom bij ONS zonder verificatiecode."))
    }
}
