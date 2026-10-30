# NEO TV

Optimiza tu Android TV / Google TV desde el celular, **sin root y sin borrar nada de forma permanente**. Y si quieres, cámbiale la pantalla de inicio por una más limpia y contrólala desde el celular.

Lo hice para mi TV JVC (Android TV 11, 1,3 GB de RAM) que iba lentísima. Con la optimización, en mi TV:

| | Antes | Después |
|---|---|---|
| Volver al inicio | 0,55 s | 0,20 s |
| RAM libre | 549 MB | 625 MB |

Abrir apps como YouTube o Netflix casi no cambió (5-10 %). Lo que más se nota es al moverse por los menús y al volver al inicio. Cada TV es distinta: tus números pueden ser otros.

## Qué hay aquí

| Carpeta | Qué es |
|---|---|
| [`optimizer/`](optimizer) | El optimizador. Corre dentro de la TV y todo lo que hace se puede deshacer |
| [`android/NeoRemote`](android/NeoRemote) | App para el celular o tablet Android: control remoto (puntero, voz, teclado) + **Optimizar TV** |
| [`android/NeoLauncher`](android/NeoLauncher) | NEO, pantalla de inicio para la TV + control web para iPhone (con pestaña **Limpiar**) |
| [`ios/`](ios) | App de iPhone empezada, sin probar (necesita una Mac) |

## Qué hace el optimizador

1. **Desactiva apps que la TV no necesita**. No las borra: quedan en la TV, apagadas.
2. **Acelera las animaciones** (de 1.0 a 0.5).
3. **Limpia la caché** de las apps. No toca tus cuentas ni tus datos.

Apps que puede desactivar (solo aparecen las que tu TV tiene):

| App | Por defecto |
|---|---|
| Rastreador ACR (`tv.anoki.acr.controller`): mira lo que ves para mostrarte publicidad | ✅ |
| Reportes de errores a Google | ✅ |
| Modo demostración de tienda | ✅ |
| Servicio de impresoras | ✅ |
| Google Play Juegos | ✅ |
| Protector de pantalla básico | ✅ |
| Prime Video, YouTube Music, Google Play Películas, canales gratis con publicidad | tú eliges |
| TalkBack (lector de pantalla) | tú eliges. No lo quites si alguien en casa lo usa |

Nunca toca la Play Store, los servicios de Google, los ajustes, el Wi-Fi, el Bluetooth, el sintonizador/HDMI ni la pantalla de inicio que estés usando.

**Para deshacer:** botón **Deshacer todo** en cualquiera de las apps, o `optimizar-pc --revertir`. Vuelve a activar exactamente lo que desactivó NEO y deja las animaciones como estaban.

## Antes de empezar (una sola vez)

1. En la TV: **Ajustes › Sistema › Acerca de** y pulsa **Compilación** 7 veces. Aparece "Ahora eres desarrollador".
2. **Ajustes › Sistema › Opciones de desarrollador** y activa **Depuración USB** y **Depuración por red** (en algunas marcas se llama "ADB por red" o "Depuración inalámbrica").
3. Anota la IP de la TV: **Ajustes › Red e Internet ›** tu Wi-Fi.
4. La primera vez que te conectes, la TV muestra un aviso: marca **Permitir siempre** y acepta.

## Opción 1 · Celular Android

1. Descarga `NeoRemote.apk` de [Releases](../../releases/latest) e instálalo (Android te pedirá permitir apps de fuentes desconocidas).
2. Ábrelo con el celular en el mismo Wi-Fi que la TV. Busca la TV solo.
3. Acepta el aviso en la TV.
4. Toca el engranaje ⚙ › **Optimizar TV**, revisa la lista y toca **Optimizar**.

## Opción 2 · iPhone

En iPhone no hay app todavía: se usa el control web de NEO, que corre en la propia TV.

1. Instala NEO en la TV sin PC: desde la Play Store de la TV instala **Downloader**, ábrelo y escribe
   `https://github.com/GONZALOcarreno99/neo-tv/releases/latest/download/NeoLauncher.apk`
2. Abre NEO en la TV y acepta el aviso de depuración (**Permitir siempre**).
3. En el iPhone, abre Safari en `http://IP-DE-TU-TV:8686` y escribe el código que aparece en la TV.
4. Pestaña **Limpiar** › marca las apps › **Optimizar**.

Consejo: en Safari toca Compartir › **Agregar a pantalla de inicio** y queda como una app.

## Opción 3 · PC (Windows, Mac o Linux)

Necesitas `adb` ([Platform Tools](https://developer.android.com/tools/releases/platform-tools)).

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File optimizer\optimizar-pc.ps1 -Ip 192.168.1.50
powershell -ExecutionPolicy Bypass -File optimizer\optimizar-pc.ps1 -Ip 192.168.1.50 -Revertir
```

```bash
# Mac / Linux
./optimizer/optimizar-pc.sh 192.168.1.50
./optimizer/optimizar-pc.sh 192.168.1.50 --revertir
```

O directo, sin los scripts:

```bash
adb connect 192.168.1.50:5555
adb push optimizer/optimizar.sh /data/local/tmp/
adb shell sh /data/local/tmp/optimizar.sh lista
adb shell sh /data/local/tmp/optimizar.sh aplicar      # las recomendadas
adb shell sh /data/local/tmp/optimizar.sh revertir
```

## Compilar las apps

No usan Gradle ni Android Studio, solo las herramientas del SDK. En Windows:

- Android SDK con `build-tools/35.0.0` y `platforms/android-34` (o la variable `ANDROID_HOME`)
- JDK 17 o más nuevo (`javac` y `keytool` en el PATH)

```powershell
android\NeoRemote\build.ps1      # primero: genera el ayudante que también usa NEO
android\NeoLauncher\build.ps1
```

La primera vez se crea `debug.keystore` para firmar. Es tuyo: no lo subas.

## Seguridad

La depuración por red le da control total de la TV a cualquier equipo que **tú** autorices. Por eso:

- **Acepta el aviso «Permitir depuración» solo cuando lo pediste tú.** Si aparece de la nada, alguien en tu Wi-Fi está intentando entrar: toca **Cancelar**.
- Úsalo solo en el Wi-Fi de tu casa, con contraseña (WPA2 o WPA3). No en redes públicas ni compartidas.
- Para quitar todos los permisos dados: **Opciones de desarrollador › Revocar autorizaciones de depuración**. Si ya no lo usas, apaga **Depuración por red**.
- El control web de NEO funciona por HTTP en tu red local (sin cifrar). Para entrar pide un código de 6 cifras que solo aparece en la pantalla de la TV; tras 5 intentos fallidos se bloquea, cada vez por más tiempo. Para desemparejar todos los celulares: Opciones de NEO › **Olvidar celulares**.
- El optimizador solo toca apps de su lista, guarda su registro en una carpeta donde las apps de la TV no pueden escribir y al deshacer revisa cada línea antes de usarla.
- Comprueba que el APK que bajas es el publicado: cada Release trae su SHA-256.

¿Encontraste un problema de seguridad? Abre un issue sin detalles de cómo explotarlo y te contacto.

## Aviso

Lo probé en mi TV (JVC con Android TV 11, chip MediaTek). Debería funcionar en la mayoría de Android TV y Google TV, pero cada marca cambia cosas. Úsalo bajo tu responsabilidad y, si algo se ve raro, usa **Deshacer todo**.

## Licencia

MIT
