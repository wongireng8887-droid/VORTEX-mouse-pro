Vortex Mouse Pro - update notes

Changes in this source revision:
- Floating logo size can be adjusted in the bubble menu (36dp to 96dp).
- Floating logo opacity cycles through 100%, 75%, and 50%.
- The app logo is used as a custom on-screen pointer while mouse pointer capture is active.
- FPS Look no longer starts on mouse movement. Assign a keyboard key to the FPS Look marker in the editor; that key toggles look ON/OFF.
- Mouse movement is coalesced at a shorter 6ms interval to reduce input latency while avoiding one injection for every raw event.
- Removing the app task from Recents shuts down the floating overlay; returning to the app can restore it only while the overlay preference remains enabled.

Build note: this archive contains Android Studio source. A compiled APK has not been produced or tested in this environment because Gradle/Android SDK tooling is unavailable here. Open the project in Android Studio and build the APK there.
