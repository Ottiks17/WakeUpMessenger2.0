# --- Smack ---
-keep class org.jivesoftware.** { *; }
-keep class org.igniterealtime.** { *; }
-keep class org.minidns.** { *; }
-keep class org.jxmpp.** { *; }
-dontwarn org.jivesoftware.**
-dontwarn org.minidns.**
-dontwarn javax.naming.**
-dontwarn org.xmlpull.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Room ---
-keep class androidx.room.** { *; }
