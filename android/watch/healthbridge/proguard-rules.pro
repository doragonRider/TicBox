# su starts these through app_process by name, so no code in the app refers to them.
# Any new root entry point has to be added here.
-keep class dev.ticbox.companion.healthbridge.FitnessSnapshotMain {
    public static void main(java.lang.String[]);
}
-keep class dev.ticbox.companion.healthbridge.FitnessPageMain {
    public static void main(java.lang.String[]);
}
-keep class dev.ticbox.companion.healthbridge.HealthJournalMain {
    public static void main(java.lang.String[]);
}
-keep class dev.ticbox.companion.healthbridge.FitnessHelperMain {
    public static void main(java.lang.String[]);
}
