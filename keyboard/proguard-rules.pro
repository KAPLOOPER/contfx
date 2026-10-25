# Continuum Keyboard release builds.
#
# R8 removes code nothing reaches, but names stay as written: a crash report then reads
# the same as the source, and nothing that looks a class up by name (ML Kit's native text
# recognition does) can be caught out by a renamed one.
-dontobfuscate

# Annotation-only packages the libraries mention but never load at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
