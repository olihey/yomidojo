# Release R8/ProGuard rules (PLAN.md) -- on top of AGP's own default
# proguard-android-optimize.txt. Most of the KMP/Compose stack here ships its own consumer
# rules (Coil, SQLDelight, AndroidX, Compose itself), so this file only covers the handful of
# libraries known to rely on reflection, JNI, or ServiceLoader-style discovery that R8 can't
# always trace on its own -- each one enabled for a first-time R8 pass, not maximal shrinking.

# kotlinx.serialization: the library's own consumer rules keep the generated $serializer
# machinery, but the annotation metadata it's keyed off needs to survive too.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

# AppAuth (Google/Microsoft OAuth, PLAN.md §10/§6.3) -- parses OAuth JSON responses and does
# its own Browser/CustomTabs component matching; AppAuth's own samples recommend a full keep
# rather than picking individual classes.
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# Ktor's OkHttp engine is discovered via META-INF/services (ServiceLoader), not a direct
# reference R8 can trace from our own code -- an unkept implementation here means "no HTTP
# engine found" at runtime instead of a compile error, so it's cheap to keep explicitly.
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.ktor.serialization.kotlinx.** { *; }
-dontwarn io.ktor.**

# pdfiumandroid (PLAN.md §16): a JNI binding -- AGP's default rules already keep `native`
# methods themselves, but the native side also looks up this package's classes/fields by exact
# name via JNI, which a plain native-method keep doesn't cover.
-keep class io.legere.pdfiumandroid.** { *; }
-dontwarn io.legere.pdfiumandroid.**

# androidx.security.crypto's EncryptedSharedPreferences/MasterKey (SMB password, Google/
# Microsoft AuthState storage) is backed by Google Tink, which has a long history of needing
# explicit keep rules under R8 for its primitive registration.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# smbj (SMB network share source, PLAN.md §6.2): a pure-Java SMB2/3 client with its own ASN.1/
# crypto internals (bouncycastle) -- kept wholesale rather than risking a stripped internal
# class breaking authentication or packet parsing in a way that's hard to repro without a real
# SMB server on hand.
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
# smbj's event bus (MBassador, net.engio.mbassy) has an optional javax.el (Expression Language)
# integration path that references classes with no Android implementation -- genuinely unused
# here (smbj never exercises it), R8's own missing_rules.txt output confirms these are the only
# gap on the javax.el side.
-dontwarn javax.el.**

# MBassador itself resolves each subscribed handler's invocation strategy by reflectively
# looking up a constructor with an exact parameter-type signature (SubscriptionContext) at
# runtime (PLAN.md §6.2, found via a real device crash: NoSuchMethodException on a minified/
# renamed handler class, since -keep com.hierynomus.** above doesn't cover this separate
# transitive package). Needs full names *and* full constructor signatures kept, not just
# existence -- a plain -keep still lets R8 rewrite descriptors that this lookup matches on.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**
