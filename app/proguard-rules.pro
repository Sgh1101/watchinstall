# libadb-android: Conscrypt 클래스와 메서드를 리플렉션으로 찾는다 (OpenSSLProvider, exportKeyingMaterial)
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# BouncyCastle: 알고리즘 구현을 이름으로 찾아 쓴다
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-dontwarn javax.naming.**
