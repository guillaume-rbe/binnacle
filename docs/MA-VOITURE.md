# Ma voiture : valeurs propres à ce fork (guillaume-rbe)

Ce fichier n'existe que dans le fork `guillaume-rbe/binnacle`. Il garde les valeurs relevées sur
**ma** voiture et **mon** écran, pour pouvoir tout restaurer, à côté des commits du dépôt d'origine
(`lotech/binnacle`), qui sont repris tels quels. Il est seul dans son fichier pour ne jamais gêner
les mises à jour depuis `lotech/binnacle`.

Voiture : Tesla **Model 3**, conduite à gauche. Écran : ZIQI **A007** (« Dash 3 »), Android 10,
firmware `A007-userdebug 12 SP1A.210812.016 202307271421`, ADB root possible (`adb root`, build
`userdebug`), pas de `su`. Aucun module GPS branché.

## Données CAN : ce qui diffère de FIELD_MAP.md sur cette voiture

Relevé avec le journal de Ruban LED le 23 nov. 2026 (trajet de 16 h 17 à 16 h 33, puis 17 h 26).

| Code | Champ | FIELD_MAP.md dit | Sur ma voiture |
|---|---|---|---|
| 9 | `autohandlight` | Auto Hold, `true` à l'arrêt maintenu en D/R | **frein de parking** : `true` en P (au démarrage, après s'être garé), `false` dès le passage en D, et **`false` à chaque Auto Hold** au feu (16:19, 16:20, 16:31). L'écran d'origine l'affiche en « H » au démarrage. L'Auto Hold n'a pas de champ : Ruban LED le déduit de `carspeed` = 0 en D après avoir roulé |
| 10 | `speedlimitsw` | sans description | **affichage du panneau de vitesse** : `true` dès qu'on roule après le démarrage (vers 12 km/h), `false` en P ; va avec `speedlimit` (30, 50, 70, 80) |
| 9 | `autodrivermode` | Autosteer | confirmé : `true` de 16:21:55 à 16:22:01 |
| — | limiteur de vitesse, régulateur seul (TACC) | TACC sans champ | confirmé : **aucun** champ des codes 9, 10, 11, 15 ne bouge (essai à 16:21, vitesse tenue à 53 km/h) |
| 9 | `nearlight` / `postlight` | feux | passent à `true` en D et suivent les feux automatiques en roulant |

## Réglages de l'écran faits en dehors de Binnacle

| Quoi | Valeur | Pourquoi | Annuler |
|---|---|---|---|
| `persist.adb.tcp.port` | `5555` | ADB réseau gardé après un redémarrage (auto-connexion CarPlay au démarrage) | `setprop persist.adb.tcp.port ""` |
| `persist.car.use_system_bt` | `1` (d'origine vide ; `2` du 12 au 17 nov.) | Bluetooth Android seul, nécessaire à CarPlay sans fil ; module d'origine « a007_bt » coupé | `2` = les deux, vide = Goodocom seul (casse CarPlay) ; puis redémarrer |
| `persist.bluetooth.disableabsvol` | `true` | volume Bluetooth absolu coupé | `setprop persist.bluetooth.disableabsvol ""` |
| `persist.vendor.sys.core.enabled` | `0` | plus de vidages mémoire GPS dans `/data/corefile` | `setprop persist.vendor.sys.core.enabled 1` |
| **Position** (`location_mode`) | **1 = appareil seul, GPS seul fournisseur**, posé par Ruban LED 0.24+ à chaque démarrage (le firmware remet 3) | la fenêtre de Google « L'accès à la position de l'appareil étant désactivé… » (Localiser mon appareil, *Fermer / Activer la localisation*) s'affiche quand aucun fournisseur n'est actif. L'écran n'a **pas de position par le réseau** : le mode 2 sans GPS (Ruban LED 0.19 à 0.23) revenait à tout couper, la fenêtre restait. Le mode 1 garde le GPS pour la boussole de Binnacle | `settings put secure location_mode <ancien>` (ou l'option de Ruban LED) |
| Précision de la localisation Google | coupée | pas de scans Wi-Fi (voir UNIT-CHANGES.md) | Paramètres → Position |
| Google Play Protect | analyse coupée (`package_verifier_user_consent -1`, `package_verifier_enable 0`) | signalait Ruban LED comme dangereuse à chaque démarrage | remettre `1` et `1` |
| 16 applis d'origine désactivées | YouTube, Google Maps, Assistant, recherche Google, Amap, TXZ, QQ Music/Live, iFlytek, lecteurs musique/vidéo/fichiers, notice, GPS test, easter eggs, **ZLink** | inutiles ; ZLink prenait l'iPhone à la place de CarPlay | `pm enable <paquet>` |
| `data.factory.app.enable_*` | `0` pour assistant, file_manager, google_map, mp4, youtube, txz_voice, zlink | l'écran les relit au démarrage et réactiverait les applis | remettre les valeurs d'origine |
| Écran d'appel Android | `com.android.dialer/com.android.incallui.InCallServiceImpl` désactivé | un appel CarPlay éteignait l'affichage | `pm enable …` |
| Compte d'appel CarPlay (Telecom) | désactivé | le Telecom Unisoc éteint l'écran 5 s après le début d'un appel | `telecom set-phone-account-enabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0` |
| `/data/system/appPowerSaveConfig.xml` | `com.shilapi.xcertplay` et `app.rubanled` en `autolaunch="2" secondarylaunch="2"` | sinon le gestionnaire d'énergie bloque CarPlay et Ruban LED | remettre le fichier d'origine, redémarrer |
| Applis en plus | Ruban LED (`app.rubanled`, dépôt `guillaume-rbe/ruban-led`), CarPlay de Binnacle (`com.shilapi.xcertplay`) | — | `adb uninstall <paquet>` |

## Mes réglages

- **Binnacle** : Model 3, conduite à gauche, CarPlay auto-connect *Show*, Start-up volume **75 %**,
  Network ADB activé, thème sombre, menu à gauche.
- **App CarPlay** : cible du certificat **I2C** `/dev/i2c-1` ; démarrer au démarrage **désactivé** ;
  « Signaler les appels à l'autoradio » **désactivé** (sinon les appels sont muets) ; iPhone appairé
  depuis l'écran (Paramètres Android → Bluetooth).
- **Ruban LED** : couleur permanente teinte 332 à 87 %, alerte rouge, nuit 19 h / 21 h → 7 h à 20 %,
  animations ×3, animation à l'arrêt 1 fois sur 4, bleu Autosteer, blanc qui respire en marche
  arrière, musique au branchement CarPlay.

## Mettre ce fork à jour

```bash
git fetch origin            # origin = lotech/binnacle
git checkout main
git merge origin/main       # garde ce fichier, reprend tous les commits d'origine
git push fork main          # fork = guillaume-rbe/binnacle
```
