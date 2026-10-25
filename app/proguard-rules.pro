# Continuum FX release builds.
#
# R8 removes code nothing reaches, but names stay as written, so a crash report reads the
# same as the source.
-dontobfuscate

# Shell commands go through Shizuku's own (private) newProcess, reached by reflection; left
# to itself R8 would see nothing calling it and take it out.
-keep class rikka.shizuku.Shizuku { *; }

# Annotation-only packages the libraries mention but never load at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
