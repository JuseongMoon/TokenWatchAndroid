package com.ScienceFiction.TokenWatchAndroid.localization

import com.ScienceFiction.TokenWatchAndroid.account.AccountNotice
import com.ScienceFiction.TokenWatchAndroid.domain.account.AccountMethod
import com.ScienceFiction.TokenWatchAndroid.domain.account.NicknameError

/*
 * App account (PLAYER) copy. Terminal chrome (`PLAYER`, `[ sign in ]`, `[ manage ]` …) stays English
 * and untranslated; only natural-language sentences live here. The text is the iOS original verbatim
 * (`L10n+Account.swift`), so both platforms read the same.
 */

// MARK: PLAYER box

val L10n.playerSignedOutHelp: String
    get() = choose(
        "TokenWatch 계정으로 로그인하면 닉네임과 아바타를 기기·플랫폼과 상관없이 이어서 써요. 사용량 기능은 로그인 없이도 그대로 쓸 수 있어요.",
        "Sign in to a TokenWatch account to keep your nickname and avatar across devices and platforms. Usage tracking works without signing in.",
    )
val L10n.playerDemoDisabled: String
    get() = choose("데모 중에는 TokenWatch 계정을 쓸 수 없어요.", "TokenWatch accounts aren't available in demo mode.")
val L10n.playerSigningIn: String get() = choose("로그인 중…", "signing in…")
val L10n.playerLoading: String get() = choose("프로필을 불러오는 중…", "loading profile…")
val L10n.playerNeedsProfile: String
    get() = choose("닉네임과 아바타를 정하면 가입이 끝나요.", "Choose a nickname and avatar to finish signing up.")
val L10n.playerLoadFailed: String
    get() = choose(
        "프로필을 불러오지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.",
        "Couldn't load your profile. Check your connection and try again.",
    )
val L10n.playerDeleting: String get() = choose("계정을 삭제하는 중…", "deleting account…")

// MARK: Sign-in screen

val L10n.accountLoginIntro: String
    get() = choose(
        "TokenWatch 계정은 선택 사항이에요. 로그인해도 AI 서비스 계정 정보는 서버로 보내지 않아요.",
        "A TokenWatch account is optional. Signing in never sends your AI service accounts to our server.",
    )
val L10n.accountGoogleNotConfigured: String
    get() = choose(
        "Google 로그인이 아직 설정되지 않았어요(개발 빌드 안내).",
        "Google sign-in isn't configured yet (development build notice).",
    )

// MARK: Notices (conflicts, failures)

fun L10n.accountNotice(notice: AccountNotice): String = when (notice) {
    AccountNotice.LoginFailed -> choose(
        "로그인하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
        "Couldn't sign in. Please try again in a moment.",
    )
    AccountNotice.GoogleUnavailable -> choose(
        "지금은 Google 로그인을 쓸 수 없어요. Apple로 로그인해 주세요.",
        "Google sign-in isn't available right now. Please sign in with Apple.",
    )
    AccountNotice.ExistsWithDifferentMethod -> choose(
        "이 이메일은 다른 방법으로 가입돼 있어요. 그 방법으로 로그인한 뒤 연결하세요.",
        "This email is already registered with another sign-in method. Sign in that way, then link this one.",
    )
    AccountNotice.AlreadyInUse -> choose(
        "이 로그인은 다른 TokenWatch 계정에 묶여 있어요. 그 계정을 삭제한 뒤 연결할 수 있어요.",
        "This sign-in is tied to another TokenWatch account. Delete that account to link it here.",
    )
    AccountNotice.LinkFailed -> choose(
        "연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
        "Couldn't link. Please try again in a moment.",
    )
    is AccountNotice.Linked -> choose(
        "${accountMethodName(notice.method)} 로그인을 연결했어요.",
        "Linked ${accountMethodName(notice.method)} sign-in.",
    )
    AccountNotice.SaveFailed -> choose(
        "저장하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.",
        "Couldn't save. Check your connection and try again.",
    )
    AccountNotice.DeleteFailed -> choose(
        "계정을 삭제하지 못했어요. 다시 시도해 주세요.",
        "Couldn't delete your account. Please try again.",
    )
    AccountNotice.RevokeFailed -> choose(
        "Apple 로그인 연결을 해제하지 못해 삭제를 멈췄어요. 다시 시도해 주세요.",
        "Couldn't disconnect Sign in with Apple, so deletion was stopped. Please try again.",
    )
}

/** Sign-in method names (proper nouns — the same in both languages). */
fun L10n.accountMethodName(method: AccountMethod): String = when (method) {
    AccountMethod.APPLE -> "Apple"
    AccountMethod.GOOGLE -> "Google"
}

// MARK: Nickname errors (contract codes)

fun L10n.nicknameError(error: NicknameError): String = when (error) {
    NicknameError.TOO_SHORT -> choose("닉네임은 2자 이상이어야 해요.", "Nickname must be at least 2 characters.")
    NicknameError.TOO_LONG -> choose("닉네임은 12자까지 쓸 수 있어요.", "Nickname can be up to 12 characters.")
    NicknameError.INVALID_CHARS -> choose("한글·영문·숫자·_·-만 쓸 수 있어요.", "Use only Korean, English letters, numbers, _ and -.")
    NicknameError.TAKEN -> choose(
        "이 닉네임은 쓸 수 있는 #번호가 남지 않았어요. 다른 닉네임을 써 주세요.",
        "No #numbers are left for this nickname. Please try another one.",
    )
    NicknameError.BANNED_WORD -> choose("쓸 수 없는 단어가 들어 있어요.", "That nickname contains a word that isn't allowed.")
    NicknameError.COOLDOWN -> choose("닉네임은 30일에 한 번 바꿀 수 있어요.", "You can change your nickname once every 30 days.")
    NicknameError.TERMS_REQUIRED -> choose("이용약관과 개인정보 처리방침에 동의해 주세요.", "Please agree to the Terms and Privacy Policy.")
}

/** Time until the next nickname change. */
fun L10n.nicknameCooldownRemaining(days: Int, hours: Int): String = if (lang == Lang.KO) {
    if (days > 0) "다음 변경까지 ${days}일 ${hours}시간" else "다음 변경까지 ${maxOf(hours, 1)}시간"
} else {
    if (days > 0) "next change in ${days}d ${hours}h" else "next change in ${maxOf(hours, 1)}h"
}

// MARK: Profile setup screen

val L10n.profileSetupIntro: String
    get() = choose(
        "다른 사용자에게 보일 닉네임과 아바타를 골라 주세요. 닉네임 뒤에는 구분용 #번호(예: #8571)가 붙어요. " +
            "아바타는 언제든, 닉네임은 30일에 한 번 바꿀 수 있어요.",
        "Pick the nickname and avatar others will see. A #number (like #8571) is added after your nickname to " +
            "tell people apart. You can change your avatar anytime and your nickname once every 30 days.",
    )
val L10n.nicknamePlaceholder: String get() = choose("2–12자", "2–12 chars")
val L10n.nicknameRule: String
    get() = choose("한글·영문·숫자·_·- / 2–12자", "Korean, English, numbers, _ - / 2–12 chars")
val L10n.nicknameTagHint: String
    get() = choose(
        "닉네임을 바꾸면 #번호도 새로 정해져요. 30일에 한 번 바꿀 수 있어요.",
        "Changing your nickname also gives you a new #number. You can change it once every 30 days.",
    )
val L10n.operatorAvatarNote: String
    get() = choose(
        "운영자 계정은 고른 아바타와 상관없이 전용 아바타로 보여요.",
        "Operator accounts always appear with the operator avatar, whatever you pick here.",
    )
val L10n.termsAgree: String
    get() = choose("이용약관과 개인정보 처리방침에 동의합니다", "I agree to the Terms of Service and Privacy Policy")
val L10n.termsLink: String get() = choose("이용약관 ↗", "terms ↗")
val L10n.privacyLink: String get() = choose("개인정보 처리방침 ↗", "privacy ↗")
val L10n.profileSetupQuit: String
    get() = choose(
        "가입을 그만두려면 로그아웃하거나 로그인 기록까지 삭제할 수 있어요.",
        "To stop signing up, sign out or delete the sign-in record.",
    )

// MARK: Account screen

val L10n.accountAvatarHelp: String get() = choose("아바타를 고른 뒤 저장하세요.", "Pick an avatar, then save.")
val L10n.accountLinkHelp: String
    get() = choose(
        "다른 로그인 방법을 연결하면 어느 쪽으로 로그인해도 같은 계정이 열려요.",
        "Link another sign-in method to open the same account either way.",
    )
val L10n.accountSignOutTitle: String
    get() = choose("TokenWatch 계정에서 로그아웃할까요?", "Sign out of your TokenWatch account?")
val L10n.accountSignOutMessage: String
    get() = choose(
        "이 기기에서만 로그아웃돼요. 프로필은 서버에 그대로 남아요.",
        "You'll be signed out on this device only. Your profile stays on the server.",
    )
val L10n.accountDeleteTitle: String
    get() = choose("TokenWatch 계정을 삭제할까요?", "Delete your TokenWatch account?")
val L10n.accountDeleteMessage: String
    get() = choose(
        "닉네임·아바타·로그인 연결이 서버에서 지워지고 되돌릴 수 없어요. 확인을 위해 한 번 더 로그인해요. 위 ACCOUNTS의 AI 서비스 계정은 영향을 받지 않아요.",
        "Your nickname, avatar and sign-in links will be erased from the server. This can't be undone. You'll sign in once more to confirm. Your AI service accounts under ACCOUNTS are not affected.",
    )
val L10n.accountSignOut: String get() = choose("로그아웃", "Sign out")
val L10n.accountDelete: String get() = choose("삭제", "Delete")
