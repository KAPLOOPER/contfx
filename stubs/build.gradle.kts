// Compile-time stand-ins for the Android system APIs the volume helper uses (audio focus
// policy). The app compiles against these but never packages them: on the phone, the real
// framework classes are used.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
