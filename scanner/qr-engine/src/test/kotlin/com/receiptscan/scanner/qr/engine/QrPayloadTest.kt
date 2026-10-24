package com.receiptscan.scanner.qr.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QrPayloadTest {
    @Test fun `wifi with escapes and masking`() {
        val p = QrPayloadParser.parse("""WIFI:T:WPA;S:Home\;Net;P:pa\:ss123;H:false;;""") as QrPayload.Wifi
        assertThat(p.ssid).isEqualTo("Home;Net")
        assertThat(p.password).isEqualTo("pa:ss123")
        assertThat(p.security).isEqualTo(WifiSecurity.WPA)
        assertThat(p.maskedPassword).doesNotContain("pa")
        val open = QrPayloadParser.parse("WIFI:S:Cafe;T:nopass;;") as QrPayload.Wifi
        assertThat(open.security).isEqualTo(WifiSecurity.OPEN)
    }

    @Test fun `links get warnings but never a safety guarantee`() {
        assertThat((QrPayloadParser.parse("https://example.com/menu") as QrPayload.Link).safety.warnings).isEmpty()
        assertThat((QrPayloadParser.parse("http://192.168.0.1/login") as QrPayload.Link).safety.warnings)
            .containsExactly("Not secure (no HTTPS)", "Uses a raw IP address instead of a name")
        assertThat((QrPayloadParser.parse("https://xn--pple-43d.com") as QrPayload.Link).safety.warnings).contains("Contains look-alike characters")
        assertThat((QrPayloadParser.parse("https://bank.com@evil.example") as QrPayload.Link).safety.warnings).contains("Hides the real address after an @")
    }

    @Test fun `product codes keep the raw number and validate checksums`() {
        val p = QrPayloadParser.parse("4006381333931", CodeFormat.EAN_13) as QrPayload.ProductCode
        assertThat(p.checksumValid).isTrue()
        assertThat((QrPayloadParser.parse("4006381333932", CodeFormat.EAN_13) as QrPayload.ProductCode).checksumValid).isFalse()
        assertThat(Gtin.isValid("036000291452")).isTrue()
        assertThat(Gtin.upcEToUpcA("01234565")).isEqualTo("012345000065")
    }

    @Test fun `contacts email sms geo events`() {
        val v = QrPayloadParser.parse("BEGIN:VCARD\nVERSION:3.0\nFN:Ali Khan\nTEL;TYPE=CELL:+923001234567\nEMAIL:ali@example.com\nORG:Acme\nEND:VCARD") as QrPayload.Contact
        assertThat(v.name).isEqualTo("Ali Khan")
        assertThat(v.phones).containsExactly("+923001234567")
        assertThat((QrPayloadParser.parse("mailto:a@b.com?subject=Hi%20there") as QrPayload.Email).subject).isEqualTo("Hi there")
        assertThat((QrPayloadParser.parse("SMSTO:+92300:hello") as QrPayload.Sms).message).isEqualTo("hello")
        assertThat((QrPayloadParser.parse("geo:31.5204,74.3587") as QrPayload.Geo).latitude).isEqualTo(31.5204)
        assertThat(QrPayloadParser.parse("geo:999,1")).isInstanceOf(QrPayload.Text::class.java)
        assertThat((QrPayloadParser.parse("MECARD:N:Khan,Ali;TEL:0300;;") as QrPayload.Contact).name).isEqualTo("Ali Khan")
    }

    @Test fun `repeat guard reports once per appearance`() {
        val g = RepeatGuard(1000)
        assertThat(g.shouldReport("x", 0)).isTrue()
        assertThat(g.shouldReport("x", 100)).isFalse()
        assertThat(g.shouldReport("x", 900)).isFalse()
        assertThat(g.shouldReport("x", 2500)).isTrue()
    }
}
