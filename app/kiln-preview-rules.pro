# kilnPreview only (appended to the release rules). Kiln reaches framework internals by reflection and swaps in its own
# classes by name from the guest process, so nothing in Kiln or its hidden-API helper may be renamed or removed.
-keep class com.blaze.kiln.** { *; }
-keep class com.blaze.assistant.kiln.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**
-dontwarn com.blaze.kiln.**
