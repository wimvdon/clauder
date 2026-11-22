# Project-specific R8 / ProGuard rules for the release build.
#
# Compose, AndroidX and Kotlin ship their own rules inside their libraries, so this
# file is intentionally (almost) empty. Add rules here ONLY when a release build
# breaks because R8 removed or renamed something that is used via reflection
# (for example a class loaded by name or a serialization model), and explain why.
#
# Release crash stack traces are obfuscated. Each GitHub Release has the matching
# mapping file attached so they can be decoded (see docs/testing.md).

# Keep line numbers in stack traces, but hide the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager (pulled in by Glance for the home screen widget) creates its Room database and its
# input mergers by class name. The old Room and WorkManager versions Glance brings keep only the
# classes, and R8's full mode then removes their empty constructors, so the release build crashed
# as soon as it opened ("Failed to create an instance of androidx.work.impl.WorkDatabase").
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.InputMerger { <init>(); }
