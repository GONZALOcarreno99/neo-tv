# NEO TV para iPhone (app nativa, sin probar)

> **Hoy, para iPhone usa el control web de NEO** (ver el README principal). Funciona en Safari, sin instalar nada.
> Esta carpeta es el comienzo de una app nativa para cuando haya una Mac para compilarla.

## Estado

El código está escrito pero **nunca se compiló ni se probó**: se hizo en Windows, y Apple solo deja compilar apps de iPhone con una Mac y Xcode. Seguro necesita algún ajuste la primera vez.

| Archivo | Qué hace |
|---|---|
| `NeoTV/AdbClient.swift` | Habla ADB por Wi-Fi con la TV (el mismo protocolo que `Adb.java` en la app Android) |
| `NeoTV/AdbKey.swift` | Crea la llave RSA del iPhone, firma el desafío de la TV y la convierte al formato de Android |
| `NeoTV/NeoTVApp.swift` | Pantalla: IP de la TV, lista de apps con interruptores, Optimizar y Deshacer |

La app no trae su propia lógica: sube `optimizer/optimizar.sh` a la TV y lo corre, igual que la app Android, el control web y los scripts de PC.

## Para armarla en una Mac

1. Xcode → **File › New › Project › iOS App** (SwiftUI). Nombre: `NeoTV`.
2. Borra el `ContentView.swift` y el `NeoTVApp.swift` que crea Xcode y arrastra los tres `.swift` de esta carpeta.
3. Arrastra también `../optimizer/optimizar.sh` y marca **Copy items if needed** (tiene que quedar dentro de la app).
4. En **Info**, agrega la clave `NSLocalNetworkUsageDescription` con un texto como *"Para conectarse con tu TV en el Wi-Fi de casa"*. Sin esto iOS bloquea la conexión.
5. Ejecuta en tu iPhone. Con una cuenta gratuita de Apple la app dura 7 días; para que dure más, o para publicarla, hace falta la cuenta de desarrollador (99 USD al año).

## Limitaciones conocidas

- Solo funciona con TVs que tienen **ADB por red en el puerto 5555** (la mayoría de Android TV / Google TV con "Depuración por red"). La depuración inalámbrica nueva de Android 11+ con código de emparejamiento usa TLS y no está implementada.
- Hay que escribir la IP de la TV a mano (la app Android la busca sola).
