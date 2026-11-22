# Both apps' release builds shrink and optimize their code but keep its real names.

# Health exports, diagnostics and logs report class names, and stack traces have to be readable
# without a mapping file.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Class merging could otherwise replace the exception name that those reports show.
-keepnames class * extends java.lang.Throwable
