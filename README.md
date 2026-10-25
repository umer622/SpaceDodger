# Space Dodger (Android game, story mode)

Pilot Zara ki kahani: 4 chapters, har chapter 30 second ka. Asteroids se bacho,
gems jama karo (5 gems = 1 extra jaan) aur Zameen tak pahuncho.

## APK banane ke 2 tareeqe

### Tareeqa 1: GitHub se (laptop par kuch install nahi karna)
1. github.com par free account banao aur naya repository banao.
2. Is poore folder ki files repository mein upload karo
   (`.github` folder samet, wohi APK banata hai).
3. Repository ke `Actions` tab mein jao, `Build APK` chalne do (2-5 minute).
4. Complete hone par us run mein neeche `SpaceDodger-apk` download karo.
   Zip kholo, andar `app-debug.apk` hai.
5. APK ko phone mein bhejo aur install karo (Unknown sources allow karna hoga).

### Tareeqa 2: Android Studio se
1. Android Studio install karo, `File > Open` se yeh folder kholo, Gradle sync hone do.
2. `Build > Build Bundle(s) / APK(s) > Build APK(s)`
3. APK yahan milega: `app/build/outputs/apk/debug/app-debug.apk`

## Technical
- Package: `com.spacedodger.game`, minSdk 26 (Android 8.0), targetSdk 35
- Main code: `app/src/main/java/com/spacedodger/game/GameView.kt`
