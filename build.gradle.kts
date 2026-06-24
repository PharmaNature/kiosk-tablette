// Build racine. AGP 9 intègre Kotlin nativement :
// on n'applique donc AUCUN plugin org.jetbrains.kotlin.android et on ne déclare
// AUCUNE version de Kotlin (cela casserait la synchronisation).
plugins {
    alias(libs.plugins.android.application) apply false
}
