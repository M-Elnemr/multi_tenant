# Flutter and its plugins ship their own consumer rules; these only silence optional references R8 cannot see.
-dontwarn com.google.android.play.core.**
-dontwarn io.flutter.embedding.engine.deferredcomponents.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
