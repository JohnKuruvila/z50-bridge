# R8 shrinking is applied by proguard-android-optimize.txt.
# OkHttp, Compose, and DataStore ship with their own consumer rules.

# Room generates a <Database>_Impl class per database and instantiates it by
# name through reflection, so R8 must not rename or remove it. WorkManager
# builds androidx.work.impl.WorkDatabase that way at process start, through
# androidx.startup. Without these rules the app installs fine and then dies on
# the first launch of a release build with:
#   Unable to get provider androidx.startup.InitializationProvider:
#   Failed to create an instance of androidx.work.impl.WorkDatabase
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
