# Keep serialized Retrofit/Gson model fields when release shrinking is enabled.
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.multibrowser.antidetect.network.** { *; }
