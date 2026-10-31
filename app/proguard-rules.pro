# DropLink R8 rules.
#
# Verifying them: build the release APK, install it, then Settings → "Run self-test". The self-test
# runs a full TLS transfer (BouncyCastle certificate + Conscrypt + protocol + storage) inside the
# release build, so a missing keep rule shows up as a failure there instead of in the field.

# --- BouncyCastle -------------------------------------------------------------------------------
# Only the certificate builder and JCA helpers are used (the BC security provider is NOT
# registered), but ASN.1/X.509 classes are looked up by OID tables; keep them whole.
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.jcajce.util.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- ZXing ---------------------------------------------------------------------------------------
-dontwarn com.google.zxing.**

# --- App -----------------------------------------------------------------------------------------
# Protocol JSON is hand-written (no reflection), Room ships its own consumer rules, Compose and
# coroutines likewise. Keep line numbers so crash logs from users are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
