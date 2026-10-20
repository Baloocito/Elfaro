# Faro Hundido

Roguelike de acción con arte de 32 bits, hecho en Kotlin con libGDX + KTX.
La luz del farol es tu vida, tu vista y tu dinero.

Documento de diseño: https://claude.ai/artifact/19NzaxyfYguXcdFwhGeZio

## Descargar el último build

Cada push a `main` compila el juego en GitHub Actions y actualiza la release **dev**:

- **Android:** `FaroHundido.apk` (Releases → *Build de desarrollo*)
- **PC:** `FaroHundido.jar`, que necesita Java 17 o superior: `java -jar FaroHundido.jar`

El APK siempre se firma con la misma keystore de desarrollo (`android/debug.keystore`),
así que cada versión nueva se instala encima de la anterior sin perder nada.

## Estado: F0 · Prototipo de sensación

Lo que pide la hoja de ruta: que moverse y golpear se sienta bien, con cuadrados de colores.

- Movimiento con aceleración (0,08 s) y frenado (0,06 s) a 96 px/s.
- **Destello**: 56 px en 0,16 s, 0,12 s de invulnerabilidad y 0,55 s de recarga.
- **Farol**: combo de 3 golpes con frame data 4/3/8. El 3.er golpe aplica Quemadura.
- **Haz**: cuesta 8 de Luz, dura 2 s, hace 30 de daño por segundo y atraviesa enemigos.
- **Luz** como vida: el radio de visión es 48 + 1,2·Luz. Los enemigos sueltan Luz, y con
  menos de 25 de Luz sueltan un 50 % más.
- Tres enemigos del Muelle definidos en `assets/data/enemies.json`:
  Ahogado, Medusa-farol y Cangrejo-relicario.
- Salas con oleadas según el presupuesto de §6. El piso sube cada 3 salas.
- Luz por bandas con tramado Bayer 4×4 (`assets/shaders/final.frag`), compatible con GLES 2.
- Hit-stop, temblor de cámara, búfer de entrada de 0,12 s y escalado entero 480×270.

## Controles

| Acción   | PC                     | Android                        |
|----------|------------------------|--------------------------------|
| Mover    | WASD / flechas         | Joystick (mitad izquierda)     |
| Apuntar  | Ratón                  | Auto-apuntado en el cono       |
| Farol    | Clic izq. (mantener = combo) / J | Botón FAROL           |
| Destello | Espacio / Shift        | Botón DESTELLO                 |
| Haz      | Clic der. / K / Q      | Botón HAZ                      |
| Debug    | F1 cajas y FPS · F2 luz on/off | —                      |

## Estructura

```
core/src/main/kotlin/cl/xiombo/faro/
  FaroGame.kt          KtxGame
  screen/RunScreen.kt  bucle de paso fijo a 60 Hz
  world/               World, Room, Player, Enemies, Fx
  render/Renderer.kt   escena → luz → shader final → HUD
  input/               teclado, ratón y táctil
  data/                Balance (números del documento), Pal (paletas), EnemyDef
assets/                shaders, data
lwjgl3/                lanzador de PC
android/               lanzador de Android
```

`data/Balance.kt` reúne todos los números del documento de diseño. Para ajustar la sensación
del juego, empieza por ahí.

## Compilar en local

```
./gradlew lwjgl3:run              # PC
./gradlew android:assembleDebug   # APK (necesita Android SDK)
```

## Siguiente fase: F1 · Núcleo roguelike

Generador de pisos 9×7, plantillas de Tiled, tienda, exvotos, la Luz como moneda y la Sirena de Brea.
