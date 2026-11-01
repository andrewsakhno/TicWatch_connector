# Shrink only: names and line numbers stay readable in watch logs (get_watch_logs, crash records)
# for about 400 KB of dex.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Protobuf-lite resolves message fields by name at runtime; Health Services' DataType initializer dies without them.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * extends androidx.wear.protolayout.protobuf.GeneratedMessageLite { <fields>; }
