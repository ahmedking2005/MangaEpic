    -optimizationpasses 8
-dontobfuscate
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext

-keep class org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment

-keep class org.koitharu.kotatsu.core.exceptions.* { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }
-keep class org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }
-keep class org.jsoup.parser.Tag
-keep class org.jsoup.internal.StringUtil

-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService
# Loaded by ACRA through ServiceLoader (META-INF/services), so nothing references it directly.
-keep class org.koitharu.kotatsu.core.util.SentryReportSenderFactory { *; }

# --- Manga Epic mediation (Mintegral + InMobi + Unity Ads via AdMob) ---
# All three adapters and SDKs ship their own consumer rules in their AARs (Google's
# Unity guide specifies no ProGuard rules at all); the keeps below are precautionary
# so R8 never strips the adapter entry points AdMob loads by name, and so mediation
# error reports keep readable class names.
-keep class com.google.ads.mediation.mintegral.** { *; }
-keep class com.google.ads.mediation.inmobi.** { *; }
-keep class com.google.ads.mediation.unity.** { *; }
-keep class com.mbridge.** { *; }
-keep class com.inmobi.** { *; }
-keep class com.unity3d.ads.** { *; }
-dontwarn com.mbridge.**
-dontwarn com.inmobi.**
-dontwarn com.unity3d.ads.**
