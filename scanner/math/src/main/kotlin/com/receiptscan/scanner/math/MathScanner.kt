package com.receiptscan.scanner.math

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.FieldState
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.skin
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.math.engine.MathAnswer
import com.receiptscan.scanner.math.engine.MathResult
import com.receiptscan.scanner.math.engine.MathSolver
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.OcrScanFlow
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.ui.SourcePhoto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MathPayload(val expression: String, val answer: String, val steps: List<String>, val check: String?)

data class MathScan(val readText: String, val image: Bitmap?, val sourceIds: List<String>)

/**
 * Math (S-Math): the recognised expression is shown as "We read" and stays editable; a strict parser and
 * deterministic solvers produce the answer, steps and a check. General OCR is not a formula recognizer.
 */
object MathScanner : ScannerModule<MathScan> {
    val ID = CapabilityId("math")
    override val info = ModuleInfo(ID, "Math", "Solve printed arithmetic and equations", ModuleColor.MATH, AppIcons.math,
        listOf("One problem at a time", "Check “We read” before trusting the answer"))

    /** Picks the line most likely to be the problem: the longest line containing an operator or "=". */
    fun pickExpression(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.filter { l -> l.any { it in "+-−×x*/÷=^√()" } && l.any { it.isDigit() } }.maxByOrNull { it.length } ?: lines.firstOrNull().orEmpty()
    }

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<MathScan>) -> Unit, onCancel: () -> Unit) {
        val src = input.source
        if (src is ScanSource.Text) { LaunchedEffect(src) { onResult(ScanOutcome.Success(MathScan(src.text, null, emptyList()))) }; return }
        OcrScanFlow(info, input, host, noun = "problem", onCancel = onCancel, onFailed = onResult, onDocument = { cap ->
            onResult(ScanOutcome.Success(MathScan(pickExpression(cap.document.fullText), cap.images.firstOrNull(), cap.sourceIds)))
        })
    }

    @Composable
    override fun Result(result: MathScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        var expr by remember(result) { mutableStateOf(result.readText) }
        val solved = remember(expr) { MathSolver.solve(expr) }
        val answerText = when (solved) {
            is MathResult.Solved -> when (val a = solved.answer) {
                is MathAnswer.Value -> a.display
                is MathAnswer.Roots -> a.roots.joinToString("  or  ") { "${a.variable} = $it" }
                is MathAnswer.NoSolution -> "No solution"
                is MathAnswer.AllValues -> "True for every ${a.variable}"
            }
            is MathResult.Error -> null
        }
        ResultScaffold(
            info, "Math", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("Copy", { onAction(ResultAction.Copy("$expr\n= ${answerText ?: ""}")) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.copy, enabled = answerText != null, fill = true)
                    if (host.capabilities.canSave) AppButton("Save", {
                        val s = solved as? MathResult.Solved ?: return@AppButton
                        onAction(ResultAction.Save(ScanEnvelope(ID, 1, expr.take(60), answerText ?: "", Json.encodeToString(MathPayload.serializer(), MathPayload(expr, answerText ?: "", s.answer.steps, s.answer.check)), result.sourceIds)))
                    }, Modifier.weight(1f), enabled = answerText != null, fill = true)
                }
            },
        ) {
            ModuleHeader(info, "We read", "Edit it if anything is wrong")
            SourcePhoto(result.image)
            AppTextField(
                expr, { expr = it }, "Expression", textStyle = AppType.mono.copy(fontSize = 18.sp),
                state = if (solved is MathResult.Error && solved.kind == MathResult.Kind.SYNTAX) FieldState.ERROR else FieldState.DEFAULT,
                helper = (solved as? MathResult.Error)?.message,
                badge = { SourceBadge(if (expr != result.readText) ValueSource.EDITED else ValueSource.FROM_PHOTO) },
            )
            Spacer(Modifier.height(12.dp))
            when (solved) {
                is MathResult.Solved -> {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column {
                            Row { Text("Answer", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f)); SourceBadge(ValueSource.CALCULATED) }
                            Text(answerText ?: "", style = AppType.d1, color = t.text)
                            Text("Read as ${solved.readAs}", style = AppType.cap, color = t.muted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (solved.answer.steps.isNotEmpty()) {
                        Text("Steps", style = AppType.h2, color = t.text)
                        solved.answer.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = AppType.mono, color = t.text, modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                    solved.answer.check?.let { Banner(BadgeKind.OK, "Check", it) }
                    (solved.answer as? MathAnswer.NoSolution)?.let { Banner(BadgeKind.INFO, it.reason) }
                }
                is MathResult.Error -> when (solved.kind) {
                    MathResult.Kind.SYNTAX -> Banner(BadgeKind.WARN, "Can't read this as math", "Fix the expression above. Supported: + − × ÷ ^ √ ( ) and one variable with “=”.")
                    MathResult.Kind.DOMAIN -> Banner(BadgeKind.ERR, "Not defined", solved.message)
                    MathResult.Kind.UNSUPPORTED -> Banner(BadgeKind.INFO, "Not supported yet", solved.message + ". Supported: arithmetic, linear and quadratic equations.")
                }
            }
            AppButton("Scan another", { onAction(ResultAction.ScanAgain) }, variant = ButtonVariant.GHOST, fill = true)
        }
    }
}
