# Solar Scope — App nativa (Android + iOS) · Ignotek

App nativa de Solar Scope con **identificación de llamadas** del cliente:
burbuja flotante en Android y Call Directory en iPhone, y registro automático
al colgar.

## Cómo está armada
- **Capacitor** envuelve la plataforma Solar Scope. La app **carga la web en
  vivo** (`https://solarscopecrm.com/calculadora/app/`), así que **cada ZIP que
  subes al Buzón actualiza también la app móvil — sin recompilar**.
- Lo **nativo** (detección de llamadas, burbuja Android, Call Directory iOS,
  registro al colgar) vive en `/android` y `/ios` como código nativo.

## Compilación (en la nube, sin Mac ni instalar nada)
- `.github/workflows/android.yml` compila el **APK de Android** en los
  servidores de GitHub y lo deja listo para descargar.
- iOS se compila en un runner macOS de GitHub + cuenta de Apple (firma).

## Hitos
1. **Shell instalable** (esto): la app abre Solar Scope en el teléfono. ← LISTO para compilar
2. **Llamadas Android**: detección de llamada + burbuja + registro al colgar (requiere prueba en teléfono real).
3. **Llamadas iPhone**: Call Directory (caller ID) + aviso al colgar (requiere cuenta Apple + prueba en iPhone real).
4. **Tiendas**: Play Store + App Store / TestFlight.

appId: `com.ignotek.solarscope` · appName: `Solar Scope`
