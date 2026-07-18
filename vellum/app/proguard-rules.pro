# JNI entry points are resolved by name from the bundled sherpa-onnx library.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep generic/signature metadata used by Readium and JSON model adapters.
-keepattributes Signature,InnerClasses,EnclosingMethod
