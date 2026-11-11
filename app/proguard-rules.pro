# CardVault shrink rules.
# Keep crash-lineage readable for an offline vault app.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ML Kit discovers these registrars from manifest metadata and instantiates
# them by reflection. Without this rule R8 strips their no-arg constructors,
# MlKitInitProvider fails, and the first OCR call crashes with an NPE.
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
