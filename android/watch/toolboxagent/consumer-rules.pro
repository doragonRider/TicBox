# The phone/bridge starts this class through app_process, not an ordinary code reference.
-keep class dev.ticbox.companion.toolboxagent.ToolboxAgentMain { public static void main(java.lang.String[]); }
# The native asset resolves these exact JNI names, also in standalone app_process.
-keep class dev.ticbox.companion.toolboxagent.PtyNative { *; }

-keep class dev.ticbox.companion.toolboxagent.ScreenNative { *; }
