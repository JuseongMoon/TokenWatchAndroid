package com.ScienceFiction.TokenWatchAndroid.ui.add

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.CursorAuth
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.GrokOAuthClient
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AuthKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiUsageClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D021 add-agent guidance, matching iOS `AddAgentGuidanceTests` (b313f3d): the notice above Claude's
 * sign-in button and the API key guides are the iOS strings byte for byte, and the API key field's
 * paste button reads the clipboard only when it is pressed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddAgentGuidanceTest {
    private val ko = L10n(Lang.KO)
    private val en = L10n(Lang.EN)

    // region Claude sign-in notice

    @Test fun claudeNoticeIsTheIosStringByteForByte() {
        assertEquals(
            "다음 화면에서 Anthropic 로그인과 권한 동의를 진행합니다. TokenWatch는 이 연결을 계정 이메일·조직 이름 표시와 남은 사용량·리셋 시간 조회에만 쓰며, 메시지를 보내지 않습니다. 로그인 토큰은 이 기기의 보안 저장소에만 저장되고 개발자 서버로 보내지 않습니다.",
            ko.claudeSignInNotice,
        )
        assertEquals(
            "Next, you'll sign in to Anthropic and review the permission request. TokenWatch uses this connection only to show your account email and organization name and to read your remaining usage and reset times. It never sends messages. Your sign-in token is stored only in this device's secure storage and is never sent to our servers.",
            en.claudeSignInNotice,
        )
    }

    @Test fun onlyClaudeShowsANoticeAboveItsSignInButton() {
        for (provider in AgentProvider.entries) {
            for (loc in listOf(ko, en)) {
                if (provider == AgentProvider.CLAUDE) {
                    assertEquals(loc.claudeSignInNotice, loc.signInNotice(provider))
                } else {
                    assertNull("$provider", loc.signInNotice(provider))
                }
            }
        }
    }

    @Test fun theClaudeNoticeMakesNoUnfoundedClaims() {
        for (text in listOf(ko.claudeSignInNotice, en.claudeSignInNotice)) {
            for (banned in listOf("공식", "표준", "official", "standard", "Claude Code")) {
                assertFalse(banned, text.contains(banned))
            }
        }
    }

    // endregion

    // region API key guides

    @Test fun apiKeyGuidesAreTheIosStringsByteForByte() {
        val expected = mapOf(
            AgentProvider.KIMI to (
                "Kimi Code 콘솔에서 이 앱 전용 API 키를 새로 만들어 붙여넣으세요. 이 키로 쿼터를 사용할 수도 있습니다." to
                    "Create a new API key just for this app in the Kimi Code console and paste it here. A key can also spend your quota."
                ),
            AgentProvider.OPENROUTER to (
                "일반 API 키가 아니라 관리 키(Management Key)가 필요합니다. OpenRouter 설정 > Management Keys에서 만든 키를 붙여넣으세요." to
                    "This needs a Management Key, not a regular API key. Paste a key created under Settings > Management Keys in OpenRouter."
                ),
            AgentProvider.DEEPSEEK to (
                "DeepSeek 플랫폼의 API keys 페이지에서 키를 만들어 붙여넣으세요." to
                    "Create a key on the DeepSeek Platform's API keys page and paste it here."
                ),
            AgentProvider.POE to (
                "Poe의 API key 페이지에서 키를 복사해 붙여넣으세요." to
                    "Copy your key from Poe's API key page and paste it here."
                ),
            AgentProvider.ELEVENLABS to (
                "ElevenLabs 설정의 API Keys에서 키를 만들어 붙여넣으세요." to
                    "Create a key under API Keys in your ElevenLabs settings and paste it here."
                ),
        )
        for ((provider, strings) in expected) {
            assertEquals("$provider ko", strings.first, ko.apiKeyGuide(provider))
            assertEquals("$provider en", strings.second, en.apiKeyGuide(provider))
        }
    }

    @Test fun everyApiKeyProviderHasAGuideInBothLanguagesAndNoOneElseDoes() {
        for (provider in AgentProvider.entries) {
            val koGuide = ko.apiKeyGuide(provider)
            val enGuide = en.apiKeyGuide(provider)
            if (provider.authKind == AuthKind.API_KEY) {
                assertFalse("$provider", koGuide.isNullOrEmpty())
                assertFalse("$provider", enGuide.isNullOrEmpty())
                assertNotEquals("$provider", koGuide, enGuide)
            } else {
                assertNull("$provider", koGuide)
                assertNull("$provider", enGuide)
            }
        }
    }

    @Test fun openRouterSaysAManagementKeyIsNeededBeforeAnythingIsTyped() {
        assertTrue(ko.apiKeyGuide(AgentProvider.OPENROUTER)!!.startsWith("일반 API 키가 아니라 관리 키(Management Key)"))
        assertTrue(en.apiKeyGuide(AgentProvider.OPENROUTER)!!.startsWith("This needs a Management Key, not a regular API key."))
    }

    @Test fun theSecureStorageMessageDoesNotAskToSignInAgain() {
        assertEquals("기기 보안 저장소를 잠시 읽지 못했습니다. 잠시 후 다시 시도해 주세요.", ko.errCredentialUnavailable)
        assertEquals(
            "Couldn't read this device's secure storage just now. Please try again in a moment.",
            en.errCredentialUnavailable,
        )
        assertFalse(ko.errCredentialUnavailable.contains("로그인"))
        assertFalse(en.errCredentialUnavailable.lowercase().contains("sign in"))
        assertFalse(en.errCredentialUnavailable.lowercase().contains("log in"))
    }

    // endregion

    // region Paste button

    private val unusedTransport = NetworkTransport { error("no network in these tests") }
    private val providerAuth = ProviderAuthRegistry(
        claudeOAuth = ClaudeOAuthClient(unusedTransport),
        codexOAuth = CodexOAuthClient(unusedTransport),
        grokOAuth = GrokOAuthClient(unusedTransport),
        copilotDeviceFlow = CopilotDeviceFlow(unusedTransport),
        cursorAuth = CursorAuth(unusedTransport),
        kimi = KimiUsageClient(unusedTransport),
    )
    private val onAddAgent: suspend (AgentProvider, OAuthTokens) -> Unit = { _, _ -> }
    private lateinit var viewModel: AddAgentFlowViewModel
    private var clipboardReads = 0

    private fun clipboard(text: String?): () -> String? = {
        clipboardReads += 1
        text
    }

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        viewModel = AddAgentFlowViewModel()
    }

    @After fun tearDown() {
        viewModel.cancelAndReset()
        Dispatchers.resetMain()
    }

    @Test fun openingTheKeyScreenAndTypingReadNoClipboard() {
        viewModel.selectProvider(AgentProvider.OPENROUTER, providerAuth, en, onAddAgent)
        viewModel.updateApiKey("typed")

        assertEquals(0, clipboardReads)
        assertEquals("typed", viewModel.apiKeyText)
    }

    @Test fun pressingPasteReadsTheClipboardOnceAndFillsTheField() {
        viewModel.selectProvider(AgentProvider.DEEPSEEK, providerAuth, en, onAddAgent)

        viewModel.pasteApiKey(clipboard("  sk-synthetic-key\n"))

        assertEquals(1, clipboardReads)
        assertEquals("sk-synthetic-key", viewModel.apiKeyText)
    }

    @Test fun anEmptyClipboardLeavesTheFieldAsItIs() {
        viewModel.selectProvider(AgentProvider.POE, providerAuth, en, onAddAgent)
        viewModel.updateApiKey("typed")

        viewModel.pasteApiKey(clipboard("   "))
        viewModel.pasteApiKey(clipboard(null))

        assertEquals(2, clipboardReads)
        assertEquals("typed", viewModel.apiKeyText)
    }

    @Test fun pasteDoesNothingOutsideTheKeyScreen() {
        viewModel.pasteApiKey(clipboard("sk-synthetic-key"))
        viewModel.selectProvider(AgentProvider.CLAUDE, providerAuth, en, onAddAgent)
        viewModel.pasteApiKey(clipboard("sk-synthetic-key"))

        assertEquals(0, clipboardReads)
        assertEquals("", viewModel.apiKeyText)
    }

    @Test fun aPastedKeyIsStillClearedFromTheFieldOnSubmit() {
        viewModel.selectProvider(AgentProvider.ELEVENLABS, providerAuth, en, onAddAgent)
        viewModel.pasteApiKey(clipboard("sk-synthetic-key"))

        viewModel.submitApiKey(providerAuth, en, onAddAgent)

        assertEquals("", viewModel.apiKeyText)
    }

    // endregion
}
