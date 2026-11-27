# Keep MeshNet core, kit, and android-kit public engine APIs and security providers
-keep public class com.herabarman.meshnet.core.** { public protected *; }
-keep public class com.herabarman.meshnet.kit.** { public protected *; }
-keep public class com.herabarman.meshnet.android.** { public protected *; }
-keep public class com.herabarman.meshnet.sim.** { public protected *; }
-keep public class com.herabarman.meshnet.dashboard.** { public protected *; }

# Keep Jetpack Compose / ViewModel entry points
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep Nearby Connections callbacks and Play Services references
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn org.bouncycastle.**
