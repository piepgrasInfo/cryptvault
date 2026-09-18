# Project-specific ProGuard/R8 rules.

# Preserve line number information so opt-in crash reports are useful.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
