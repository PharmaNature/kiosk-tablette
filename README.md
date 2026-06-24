# Kiosk PharmaNature — Tablette Android verrouillée (WebView)

App Android transformant une tablette en **kiosk verrouillé**.

**Fonctionnement :**
1. À l'ouverture, l'app affiche un **écran de configuration** : URL, code PIN, nombre de
   tapotements et **coin déclencheur**.
2. On appuie sur **« Lancer le mode kiosk »** → la tablette passe en **plein écran verrouillé**
   sur l'URL, impossible d'en sortir.
3. Pour revenir à la config : **N tapotements dans le coin choisi** → saisie du **PIN** → écran admin.

- **URL par défaut** : `http://172.16.40.54:5173/` (modifiable dans l'écran admin, sans recompiler)
- **PIN initial** : `1234` — ⚠️ **à changer** dans l'écran admin (champ PIN)
- **Verrouillage** : mode **Device Owner** (lock task, pas de barre système, relance au boot,
  ADB / factory reset / safe boot désactivés). Le verrouillage réel exige le provisioning ADB (§4).

---

## 1. Prérequis (poste de build)

Cette machine n'a **rien d'installé** pour Android. Installez :

1. **Android Studio** (dernière version) — embarque le JDK 17, le SDK, `adb` et Gradle.
   → https://developer.android.com/studio
2. Dans **SDK Manager** (Settings → Languages & Frameworks → Android SDK), cochez :
   - **Android 16.0 (API 36)** — *SDK Platform*
   - **Android SDK Build-Tools 36**
   - **Android SDK Platform-Tools** (fournit `adb`)

> Le wrapper Gradle (9.1.0) est déjà inclus : aucune installation de Gradle séparée n'est requise.

---

## 2. Ouvrir et compiler

### Option A — Android Studio (recommandé)
1. **File → Open** → sélectionnez le dossier `kioske`.
2. Laissez la synchronisation Gradle se terminer (télécharge AGP 9.0.2 + dépendances).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)** pour un APK de test (debug),
   ou voir §3 pour un APK release signé.

### Option B — ligne de commande (PowerShell, à la racine `kioske`)
```powershell
# APK debug (non signé pour la prod, mais retirable facilement — voir §5)
.\gradlew.bat assembleDebug
# Résultat : app\build\outputs\apk\debug\app-debug.apk
```

---

## 3. Build release signé (pour déploiement)

1. **Générer un keystore** (une seule fois), à la racine du projet :
   ```powershell
   keytool -genkeypair -v -keystore release.keystore -alias kiosk `
       -keyalg RSA -keysize 4096 -validity 10000 `
       -dname "CN=PharmaNature Kiosk, O=PharmaNature, C=FR"
   ```
   (`keytool` est fourni avec le JDK d'Android Studio.)
2. **Créer `keystore.properties`** à la racine (copie de `keystore.properties.example`) :
   ```properties
   storeFile=../release.keystore
   storePassword=VOTRE_MDP
   keyAlias=kiosk
   keyPassword=VOTRE_MDP
   ```
   > `release.keystore` et `keystore.properties` ne doivent **jamais** être committés
   > (déjà exclus par `.gitignore`). Conservez-les en lieu sûr : sans eux, pas de mise à jour possible.
3. **Compiler** :
   ```powershell
   .\gradlew.bat assembleRelease
   # Résultat : app\build\outputs\apk\release\app-release.apk
   ```

---

## 4. Mise en service d'une tablette (provisioning Device Owner)

> ⚠️ **Le mode kiosk verrouillé n'est actif que si l'app est Device Owner.** Sans cette étape,
> l'app s'ouvre en plein écran mais l'utilisateur peut en sortir.

### Prérequis **incontournables** sur la tablette
- Tablette **fraîchement réinitialisée** (factory reset).
- **AUCUN compte** configuré : ni Google, ni Samsung. *(Sur Samsung, supprimez aussi le Secure Folder,
  qui compte comme un compte.)* Sautez l'ajout de compte dans l'assistant de démarrage.
- **Options développeur** activées + **Débogage USB** activé.
- La tablette visible par `adb` : `adb devices` doit la lister.

### Commandes (poste de build, tablette branchée en USB)
```powershell
adb install app-release.apk
adb shell dpm set-device-owner fr.pharmanature.kiosk/.KioskAdminReceiver
# Attendu : "Success: Device owner set to package fr.pharmanature.kiosk"
adb reboot
```

Après redémarrage, la tablette démarre **directement sur le kiosk** et y reste verrouillée.

**Vérifier le statut Device Owner :**
```powershell
adb shell dumpsys device_policy | findstr "Device Owner"
```

---

## 5. Sortie, maintenance et retrait

### Accès admin (sur la tablette)
**N tapotements** (le nombre choisi) dans le **coin choisi** → saisir le **PIN** → écran d'administration :
- Modifier l'**URL**, le **PIN**, le **nombre de tapotements**, le **coin** (tous **obligatoires**)
- Case **« Compatible contrôle à distance (RustDesk) »** (rendu logiciel, évite l'écran noir)
- **Lancer le mode kiosk** (applique les réglages et re-verrouille)
- **Arrêter le kiosk (déverrouiller)** : repasse sur l'écran de config sans verrou (maintenance)
- **Désactiver complètement le mode kiosk** : retire le statut Device Owner **sans factory reset**

### Retrait via ADB (builds **debug** uniquement)
Un build **debug** est `testOnly` → retrait possible sans factory reset :
```powershell
adb shell dpm remove-active-admin fr.pharmanature.kiosk/.KioskAdminReceiver
```
Un build **release** n'est pas `testOnly` : utilisez le bouton « Désactiver complètement le mode kiosk »
de l'écran admin, ou en dernier recours un **factory reset**.

---

## 6. Personnalisation rapide

| Quoi | Où |
|---|---|
| URL par défaut | `KioskConfig.DEFAULT_URL` (et `app/src/main/res/xml/network_security_config.xml` si l'hôte change) |
| PIN par défaut | `KioskConfig.DEFAULT_PIN` |
| Nb de tapotements par défaut | `KioskConfig.DEFAULT_TAPS` |
| Coin / délai de détection | `KioskActivity.CORNER_DP` / `TAP_WINDOW_MS` |
| Nom / icône de l'app | `res/values/strings.xml` (`app_name`) / `res/drawable/app_icon.xml` |

> **Important — HTTP en clair :** l'URL est en `http://` (non chiffré). Le fichier
> `network_security_config.xml` n'autorise le trafic en clair que vers `172.16.40.54`.
> Si vous changez d'hôte, **mettez ce fichier à jour**, sinon le chargement échouera.

---

## 7. Stack technique

| Composant | Version |
|---|---|
| AGP | 9.2.1 |
| Gradle | 9.4.1 |
| Kotlin | 2.2.x (intégré à AGP 9) |
| JDK (build) | 17 |
| compileSdk / targetSdk | 36 (Android 16) |
| minSdk | 24 (Android 7.0) |

UI : WebView unique (sans Jetpack Compose). Dépendances : androidx core-ktx, appcompat, activity-ktx.
