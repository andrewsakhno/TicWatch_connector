// phone-app and watch-app are self-contained Android Gradle projects (own wrapper,
// opened directly in Android Studio) and are intentionally NOT included here:
// applying the Android Gradle plugin at this root would require an Android SDK
// just to configure the backend build, which doesn't need one.
rootProject.name = "TicWatch_connector"

include(":backend")
