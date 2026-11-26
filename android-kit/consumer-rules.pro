# R8 / ProGuard rules for apps that include the mesh engine. The engine uses no reflection and no serialization frameworks,
# so shrinking is safe; only keep what the app calls (the public client API) and let R8 drop the rest.
-keep public class com.herabarman.meshnet.kit.MeshClient { public *; }
-keep public class com.herabarman.meshnet.kit.MeshClient$* { public *; }
-keep public class com.herabarman.meshnet.android.MeshNet { public *; }
-keep public class com.herabarman.meshnet.android.MeshNet$* { public *; }
-keep public class com.herabarman.meshnet.android.MeshService { public *; }
-keep public interface com.herabarman.meshnet.kit.RawRadio { *; }
-keep public interface com.herabarman.meshnet.kit.BulkLink { *; }
-dontwarn com.google.android.gms.**
