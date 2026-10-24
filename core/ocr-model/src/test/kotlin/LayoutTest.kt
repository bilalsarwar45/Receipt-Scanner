import com.google.common.truth.Truth.assertThat
import com.receiptscan.ocr.model.Box
import com.receiptscan.ocr.model.Layout
import com.receiptscan.ocr.model.OcrBlock
import com.receiptscan.ocr.model.OcrLine
import com.receiptscan.ocr.model.OcrPage
import com.receiptscan.ocr.model.SyntheticOcr
import com.receiptscan.ocr.model.TextNormalizer
import org.junit.Test

class LayoutTest {
    @Test fun `separate name and price lines join into one row`() {
        val name = OcrLine("Milk 1L", Box(10, 100, 120, 130))
        val price = OcrLine("640.00", Box(400, 104, 480, 132))
        val next = OcrLine("Bread", Box(10, 140, 90, 170))
        val page = OcrPage("s", 0, 500, 300, listOf(next, price, name).map { OcrBlock(it.text, it.box, listOf(it)) })
        val rows = Layout.rows(page)
        assertThat(rows.map { it.text }).containsExactly("Milk 1L  640.00", "Bread").inOrder()
        assertThat(rows[0].trailingCell.text).isEqualTo("640.00")
    }

    @Test fun `slightly skewed rows still separate`() {
        val page = SyntheticOcr.page(
            """
            FreshMart
            Milk 1L | 640.00
            Bread | 240.00
            """,
        )
        assertThat(Layout.rows(page).map { it.cells.size }).containsExactly(1, 2, 2).inOrder()
    }

    @Test fun `normalizer keys`() {
        assertThat(TextNormalizer.key("Café  Latté!")).isEqualTo("cafe latte")
        assertThat(TextNormalizer.normalize("Total  −200")).isEqualTo("Total -200")
    }
}
