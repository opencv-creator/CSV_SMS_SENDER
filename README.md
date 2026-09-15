# CSV SMS Sender - Java / Telugu UTF-8

This version is configured for an Android Studio 2022-era toolchain:

- Android Gradle Plugin: 7.4.2
- Gradle: 7.5.1
- compileSdk: 33
- targetSdk: 33
- minSdk: 23
- Java source compatibility: Android Studio/Gradle default compatible with Java 11
- Language: Java
- CSV encoding: UTF-8

## CSV format

Use:

```csv
phone,message
9876543210,"నమస్కారం! మీకు, మీ కుటుంబ సభ్యులకు శుభాకాంక్షలు."
9123456789,"మీకు ""ప్రత్యేక"" శుభాకాంక్షలు!"
```

The parser supports:
- Telugu and other Unicode text
- commas inside messages
- double quotes inside messages using `""`
- multiline quoted messages
- UTF-8 BOM

Save the CSV as **UTF-8**.

## Open in Android Studio 2022

1. Extract the ZIP.
2. Open the extracted `CSV_SMS_Sender_Java` folder in Android Studio 2022.
3. Let Android Studio sync Gradle.
4. Use **Gradle 7.5.1** and **JDK 11** for this project.
5. Connect an Android phone with a SIM and USB debugging enabled.
6. Build and run the app.
7. Grant the SMS permission when requested.
8. Select the CSV and start sending.

### Important
Android and Google Play have restrictions around SMS permissions and bulk messaging. For testing/sideloading, use numbers and messages for which you have permission/consent.

The app sends SMS through the phone's SIM using `SmsManager`; it does not require internet for the actual SMS transmission.

## Latest correction

The CSV picker callback in `MainActivity.java` uses the correct Android
`onActivityResult(int, int, Intent)` signature.
