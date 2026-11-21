# libadb 는 Conscrypt 를 이름으로 찾아 쓴다.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
# am instrument 가 이름으로 띄운다.
-keep class app.tetherfree.ApnBroker { *; }
