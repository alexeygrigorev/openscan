# OpenScan ProGuard/R8 rules.
#
# The app is small and mostly reflection-free, but a few libraries need care:
# ML Kit and Play services ship their own consumer rules; the rules below only
# cover what they do not.

# PdfBox-Android loads font resources by name and reflects over its
# java.awt-compat shims; keep what the library documents.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**

# ML Kit text recognition (bundled model) and the Play services document
# scanner talk to GMS over IPC with proto-backed parcels.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Coroutines debug metadata is safe to strip; silence warnings from
# kotlinx-coroutines internals pulled in transitively.
-dontwarn kotlinx.coroutines.debug.**
