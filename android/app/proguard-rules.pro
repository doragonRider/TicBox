# Navigation loads enum route arguments with Class.forName and reads their constants by reflection.
# Its own rules don't cover them, so a missing enum would only crash when that route opens.
-keep enum dev.ticbox.companion.ui.** { *; }

# These new Binder service APIs are intentionally delivered before their frontend callers.
# Preserve the contract and virtual implementations in an R8 build without those pages yet.
-keep interface dev.ticbox.companion.api.WatchToolbox** { *; }
-keep interface dev.ticbox.companion.api.AdbToolboxClient { *; }
-keep interface dev.ticbox.companion.api.DndSyncClient { *; }
-keep class dev.ticbox.companion.api.Toolbox** { public *; }
-keep class dev.ticbox.companion.api.DndSync** { public *; }
-keepclassmembers class dev.ticbox.companion.engine.toolbox.AgentToolboxBackend { public *; }
-keepclassmembers class dev.ticbox.companion.engine.toolbox.DndSyncEngine { public *; }
-keepclassmembers class dev.ticbox.companion.platform.toolbox.AndroidAdbToolbox { public *; }
-keepclassmembers class dev.ticbox.companion.platform.toolbox.AndroidToolboxServices { public *; }
