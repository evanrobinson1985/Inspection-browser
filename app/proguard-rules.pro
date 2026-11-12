# The app's own code is small: keep it as written, so nothing that WebView, the manifest or
# kotlinx.serialization reaches by name is renamed, and stack traces stay readable.
# R8 still shrinks and optimises every library.
-keep class com.inspectionbrowser.app.** { *; }
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
