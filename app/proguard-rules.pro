# libsu is used reflectively by the shell layer.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Our manifest-referenced components (service, receivers, tile) are kept by AGP automatically.
