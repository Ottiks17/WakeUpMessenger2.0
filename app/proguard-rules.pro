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

# --- Tink (транзитивно через androidx.security:security-crypto) ---
# com.google.errorprone:error_prone_annotations — compile-only зависимость Tink;
# на рантайме эти аннотации не нужны, R8 ругается на отсутствующие классы.
-dontwarn com.google.errorprone.annotations.**
