# Règles R8 / ProGuard pour le build release.
#
# L'app n'utilise pas addJavascriptInterface (pas de pont JS <-> Kotlin) :
# aucune règle de conservation spécifique n'est nécessaire ici.
# Les classes référencées par le manifest (KioskActivity, AdminActivity,
# KioskAdminReceiver) sont conservées automatiquement par AGP.
#
# Si vous ajoutez un jour un pont JavaScript, dé-commentez et adaptez :
# -keepclassmembers class fr.pharmanature.kiosk.JsBridge {
#     public *;
# }
