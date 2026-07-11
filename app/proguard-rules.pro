# API-key response models are private nested Kotlin classes decoded through Moshi reflection.
# Keep their Kotlin metadata and members so release minification cannot rename JSON properties.
-keep class com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.** { *; }
