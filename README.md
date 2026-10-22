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

## Estado: F2-base · Santuario y armas

Todo lo de la F1, más los requisitos previos a los biomas 2-4 (§13 del documento de diseño):

- **Santuario** entre runs. Aquí se elige el arma, el exvoto inicial y se compran las mejoras
  permanentes con Perlas. Las Perlas se ganan así: +1 por piso y +5 por jefe.
- **Cuatro armas**, cada una con su mejora:
  - Farol (gratis).
  - Arpón (se desbloquea al vencer a la Sirena).
  - Ancla (60 Perlas).
  - Campana (se desbloquea con el Obispo).
- **Aturdido**: a los jefes les dura la mitad del tiempo y luego quedan 2 s inmunes; mientras dura,
  el enemigo recibe +25 % de daño. El Ancla y los golpes contra la pared lo provocan.
- **Antesala del jefe** con el **Farol de vigilia**: cura +30 si te queda menos del 50 % de Luz,
  +20 si te queda menos del 80 % y +10 en otro caso.
- **Salas grandes** que ocupan dos celdas (horizontales o verticales).
- **Muerte legible**: se ve qué te mató, en qué sala y con cuánta Luz habías entrado.
- **Guardado de la run** al entrar en cada sala y al pausar. Se puede abandonar la run desde la pausa.

## F1 · Núcleo roguelike

Una run completa del bioma 1, el Muelle Ahogado: 3 pisos y la Sirena de Brea.

- **Pisos generados** con el algoritmo de §7 (rejilla 9×7). Incluyen tienda, tesoro, refugio,
  salas élite, una sala secreta que se abre disparando el Haz a la pared agrietada, y la
  escalera o el jefe en la sala más lejana.
- **21 plantillas de Tiled** en `assets/rooms/`: 15 de combate, las salas especiales y la arena del jefe.
- **Pozos de agua**: Isla cae con 0,1 s de coyote y pierde 10 de Luz. El Destello los cruza.
- **15 exvotos** con conjuntos ×3 (Fuego, Sal, Marea, Hueso) y dos legendarios.
- **Luz como moneda**: exvotos a 15/25/40/60 según rareza, Mecha a 30, mejora del Farol a 20
  y carta del piso a 10. Comprar nunca te deja por debajo de 1 de Luz.
- **Élites** con los afijos Salado, Brillante, Gemelo y Hambriento.
- **La Sirena de Brea**, con 900 de vida y 3 fases:
  1. Espiral de notas y abanicos dirigidos.
  2. La brea apaga tu luz un 30 %.
  3. Se sumerge y emerge bajo ti con un aviso de burbujas de 0,6 s.
- Al vencerla ganas **5 Perlas**, que quedan guardadas para el Santuario de la F2.

F0 (prototipo de sensación) está completa: movimiento, Destello, Farol, Haz, luz por bandas.

## Controles

| Acción   | PC                     | Android táctil                 | Mando (Bluetooth / USB)     |
|----------|------------------------|--------------------------------|-----------------------------|
| Mover    | WASD / flechas         | Joystick (mitad izquierda)     | Stick izquierdo o cruceta   |
| Apuntar  | Ratón                  | Auto-apuntado en el cono       | Stick derecho (auto-apuntado suave de 12°) |
| Farol    | Clic izq. (mantener = combo) / J | Botón FAROL          | RT / X (mantener = combo)   |
| Destello | Espacio / Shift        | Botón DESTELLO                 | A / RB                      |
| Haz      | Clic der. / K / Q      | Botón HAZ                      | LT / B                      |
| Usar / comprar | E / Enter        | Botón USAR (aparece cerca)     | Y                           |
| Pausa    | Esc / P                | Botón II o atrás               | Start / Select              |
| Debug    | F1 cajas y FPS · F2 luz on/off | —                      | —                           |
| Con F1   | F3 carta y Luz llena · F4 ir al jefe · F5 exvoto (en el Santuario: +50 Perlas y todas las armas) · F6 150 de daño · F7 mando virtual (teclas 1–0) | — | — |

**Mando:** se conecta por Bluetooth en los ajustes del teléfono y el juego lo detecta solo.
Al usarlo se ocultan los controles táctiles, y vuelven a aparecer al tocar la pantalla.
El mando vibra al recibir un golpe.

**Configurar mando** (en la pausa) muestra el mando dibujado, con cada botón del color de su
acción y encendido mientras lo pulsas, y una tabla de acciones con dos huecos cada una:
- Elige un hueco y pulsa **A**, luego el botón que quieras. Los gatillos analógicos también sirven.
- **X** quita un botón, **Y** vuelve a la distribución por defecto y **B** guarda y sale.
- Reglas: máximo 2 botones por acción, y un botón solo puede tener una acción. Si asignas uno
  que ya está en uso, los huecos se intercambian. Ninguna acción puede quedarse sin botón.
- Start, Select y la cruceta están reservados (pausa y movimiento), así nunca te quedas sin ellos.

Por defecto: Farol RT + X · Destello A + RB · Haz LT + B · Usar Y (LB libre).

## Estructura

```
core/src/main/kotlin/cl/xiombo/faro/
  FaroGame.kt          KtxGame
  screen/RunScreen.kt  bucle de paso fijo a 60 Hz
  world/               World, Room, Player, Enemies, Fx
  render/Renderer.kt   escena → luz → shader final → HUD
  input/               teclado, ratón y táctil
  data/                Balance (números del documento), Pal (paletas), EnemyDef
assets/                shaders, data, rooms (Tiled)
tools/                 salas.txt + gen_salas.py (plantillas → .tmx validados)
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

## Salas en Tiled

Abre cualquier `assets/rooms/*.tmx` con [Tiled](https://www.mapeditor.org/). Tiene dos capas:
- **terreno:** muro y agua.
- **marcadores:** aparición de enemigos y pedestal.

Si añades una sala, inclúyela en `assets/rooms/index.json`. Otra opción es escribirla en
`tools/salas.txt` y ejecutar `python3 tools/gen_salas.py`, que además comprueba que se pueda
llegar a todas las puertas y marcadores.

## Builds

- `main` publica la release **dev**.
- Las ramas de fase (`f1`, `f2`…) publican **preview-<rama>** para probar antes de fusionar.

## Siguiente fase: F2 · Biomas 1 y 2

Arte final del Muelle y la Catedral, 6 enemigos más, el Obispo Coral, el Santuario básico
(donde se gastan las Perlas), y el Arpón y la Campana.
