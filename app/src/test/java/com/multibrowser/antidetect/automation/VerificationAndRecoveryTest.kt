package com.multibrowser.antidetect.automation

import com.multibrowser.antidetect.automation.input.InputController
import com.multibrowser.antidetect.automation.perception.*
import com.multibrowser.antidetect.automation.recovery.RecoveryEngine
import com.multibrowser.antidetect.automation.recovery.RecoveryResult
import com.multibrowser.antidetect.automation.verification.VerificationEngine
import com.multibrowser.antidetect.automation.verification.VerificationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerificationAndRecoveryTest {

    private val verificationEngine = VerificationEngine()
    private val targetResolver = TargetResolver()

    private fun createBaseSnapshot(
        pageState: String = "PAGE_READY",
        elements: List<ElementSnapshot> = emptyList(),
        videoState: VideoPlaybackState? = null
    ): DomSnapshot {
        return DomSnapshot(
            url = "https://m.youtube.com",
            title = "YouTube",
            pageState = pageState,
            viewport = ViewportInfo(width = 412f, height = 915f, dpr = 2.625f, scrollX = 0f, scrollY = 0f),
            elements = elements,
            videoState = videoState
        )
    }

    @Test
    fun testVerifyPageStateSuccess() = runBlocking {
        var callCount = 0
        val getSnapshot: suspend () -> DomSnapshot? = {
            callCount++
            if (callCount >= 2) {
                createBaseSnapshot(pageState = "SEARCH_RESULTS")
            } else {
                createBaseSnapshot(pageState = "LOADING")
            }
        }

        val result = verificationEngine.verifyPageState(
            expectedState = "SEARCH_RESULTS",
            timeoutMs = 2000L,
            getSnapshot = getSnapshot
        )

        assertTrue(result is VerificationResult.Verified)
    }

    @Test
    fun testVerifyPageStateTimeout() = runBlocking {
        val getSnapshot: suspend () -> DomSnapshot? = {
            createBaseSnapshot(pageState = "LOADING")
        }

        val result = verificationEngine.verifyPageState(
            expectedState = "SEARCH_RESULTS",
            timeoutMs = 800L,
            getSnapshot = getSnapshot
        )

        assertTrue(result is VerificationResult.Timeout)
        val timeout = result as VerificationResult.Timeout
        assertEquals("LOADING", timeout.lastObservedState)
    }

    @Test
    fun testRecoveryScrollsUntilElementInViewport() = runBlocking {
        val mockInput = MockInputController()
        val recoveryEngine = RecoveryEngine(mockInput, targetResolver)

        var attempt = 0
        val targetSpec = TargetSpec(textSnippet = "Target Video")

        // First 2 calls: element is outside viewport (visible = false)
        // 3rd call: element entered viewport (visible = true)
        val getSnapshot: suspend () -> DomSnapshot? = {
            attempt++
            val isVisible = attempt >= 3
            createBaseSnapshot(
                elements = listOf(
                    ElementSnapshot(
                        id = "vid_target",
                        role = "link",
                        tag = "a",
                        text = "Target Video 2026",
                        ariaLabel = "Watch Target Video",
                        visible = isVisible,
                        enabled = true,
                        rect = DomRect(left = 16f, top = if (isVisible) 200f else 1200f, width = 380f, height = 200f)
                    )
                )
            )
        }

        val recoveryResult = recoveryEngine.recoverMissingTarget(
            spec = targetSpec,
            maxScrollAttempts = 4,
            getSnapshot = getSnapshot
        )

        assertTrue(recoveryResult is RecoveryResult.Resolved)
        val resolved = (recoveryResult as RecoveryResult.Resolved).target
        assertEquals("vid_target", resolved.element.id)
        assertTrue(resolved.isInsideViewport)
        assertTrue("Input controller should have performed at least 1 swipe", mockInput.swipeCount >= 1)
    }

    @Test
    fun testRecoveryEscalatesWhenElementNeverFound() = runBlocking {
        val mockInput = MockInputController()
        val recoveryEngine = RecoveryEngine(mockInput, targetResolver)

        val getSnapshot: suspend () -> DomSnapshot? = {
            createBaseSnapshot(elements = emptyList())
        }

        val result = recoveryEngine.recoverMissingTarget(
            spec = TargetSpec(textSnippet = "Completely Missing"),
            maxScrollAttempts = 2,
            getSnapshot = getSnapshot
        )

        assertTrue(result is RecoveryResult.EscalateTier2)
    }
}

class MockInputController : InputController {
    var swipeCount = 0
    var tapCount = 0

    override suspend fun tap(point: ScreenPoint) { tapCount++ }
    override suspend fun swipe(start: ScreenPoint, end: ScreenPoint, durationMs: Long) { swipeCount++ }
    override suspend fun type(text: String, wpm: Int, typoProb: Double) {}
    override suspend fun back() {}
}
