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

## Estado: F4 · Juego completo

El juego está completo de principio a fin: 12 pisos, 5 jefes más uno secreto, 3 finales, 10 Mareas, tienda con lotes, 4 náufragos y audio.

- **Mareas 1-10** (el pozo del Santuario). Ganar con la Marea M desbloquea la M+1. Las reglas se acumulan:
  1. Oleadas élite (20 %).
  2. El refugio da 20 de Luz en vez de 30.
  3. Los enemigos atacan un 10 % más seguido.
  4. La tienda es un 20 % más cara.
  5. Patrón extra en cada jefe:
     - la Sirena lanza una corona de notas;
     - el Obispo tira esquirlas en todos sus barridos;
     - el Cartógrafo dispara el compás en 8 direcciones;
     - la Lámpara saca raíces de a dos.

     Además se abre el final C.
  6. Los avisos de ataque se acortan (mínimo 0,3 s).
  7. Empiezas con 80 de Luz máxima.
  8. Niebla: el refugio ya no revela el mapa.
  9. Las élites llevan 2 afijos.
  10. Al apagarse la Lámpara se suelta **la criatura**.

  La **Marea baja** es un modo asistido: 30 % menos de daño y avisos un 30 % más largos.
- **Final C · Dejar la oscuridad**: con Marea 5+, la Lámpara empieza dormida. Si velas 3 s cerca de ella sin acercarte de más, se apaga sola y baja **el Farero Antiguo** (2.400, 3 fases). Pelea a oscuras (radio máx. 80 px):
  - Fase 1: busca con el cono de su farol. Si te ve sin velar, lanza 3 garfios.
  - Fase 2: apaga el farol y carga hacia tu luz. Si velas durante la carga, te pierde y queda expuesto (+50 % de daño).
  - Fase 3: la fosa respira cada 8 s y te quita 15 de Luz si no estás velando.
- **Náufragos** (uno por bioma, en una celda custodiada):
  - Amparo abre la tienda.
  - Bruno es un personaje jugable con +20 de Luz máxima, que es más lento y lleva la Campana mejorada.
  - Lucía abre su puesto de mapas.
  - Marina, la hija del Farero, da la pista del final C.
- **Tienda**: 30 exvotos nuevos en 5 lotes, que se compran en el Santuario (30 Perlas cada lote). Hay pesos de rareza por bioma.
- **Semillas**: código de 8 letras que se ve en la pausa, en la muerte y en la victoria, y se puede escribir en "Jugar con semilla".
- **Registro**: las 12 últimas runs, con resultado, causa, Marea, tiempo y semilla.
- **Opciones**:
  - temblor;
  - vibración;
  - destellos suaves;
  - volumen de música y de efectos.
- **Audio**: 22 efectos y 7 pistas (Santuario, los 4 biomas, jefe y oscuridad). Se generan con `python3 tools/gen_audio.py` y van en `assets/sfx` y `assets/music`.
- Niveles de prueba nuevos: "Final C" (Marea 5) y "Marea 10: la criatura".

## F3 · Los 4 biomas

La run completa baja por los 4 biomas (12 pisos) y termina en la Lámpara Madre con el final A o B.

- **Archivo de los Náufragos** (pisos 7-9):
  - Estanterías que se deslizan sobre rieles cada 6 s, con aviso de tinta 1 s antes. Aplastan: 8 de daño a Isla, 20 a los enemigos.
  - Atriles con lámpara verde: luz fija de 48 px.
  - **La Tinta y los élites Tachados solo existen dentro de tu luz**. La Campana los revela 3 s.
  - Enemigos: Tinta, Bibliotecario sin rostro (se teletransporta y deja páginas trampa), Polillas de papel (de a 4; van a la luz y el Haz las quema) y Copista (escribe líneas que estallan).
  - **Sala de lectura**: botella garantizada y reto (despejarla sin Haz da un exvoto raro).
  - **El Cartógrafo** (2.100, 3 fases):
    - Fase 1: redibuja los muros y dispara un compás en cruz.
    - Fase 2: un clon de papel repite tus últimos 3 s; si lo rompes, el Cartógrafo queda aturdido y recibe +50 % de daño.
    - Fase 3: apaga todas las luces menos la tuya y deja un rastro de tinta.
- **Raíz del Faro** (pisos 10-12):
  - Vidrio fundido: quema 4 de Luz por segundo. El latido acelera a los enemigos cada 10 s.
  - **Con más de 120 px de radio te detectan todos**: hay que velar.
  - Enemigos: Anguila de raíz (sale bajo tus pies), Hijo del faro (usa tu arma), Vidriero (deja vidrio fundido y estalla al morir) y Ojo de la fosa (rayo si tu luz es grande).
  - **Corazón de piedra**: si lo golpeas suelta 30 de Luz, pero despierta una oleada élite.
  - **La Lámpara Madre** (3.200, 4 fases):
    - Fase 1: haces giratorios y raíces bajo tus pies.
    - Fase 2: se abre y escupe Vidrieros.
    - Fase 3: ecos de la Sirena, del Obispo y del Cartógrafo.
    - Fase 4: bebe tu luz, y cada golpe que le das te la devuelve.
- Afijos nuevos: **Tachado** (Archivo) y **Hambre de luz** (Raíz).
- **40 botellas con mensaje** (Sala de lectura y salas secretas). Leerlas todas da el **final B**.
- Recuerdos de Elías (el Cartógrafo) y la nota del Farero tras la Lámpara.
- El menú de pruebas tiene los 4 biomas y sus jefes. F11 salta al bioma siguiente.

## F2 · Catedral Sumergida

**Menú principal**: al abrir el juego. Continuar descenso (si hay una run guardada), Jugar (ir al
Santuario), **Niveles de prueba** (Muelle piso 1, la Sirena, Catedral piso 4, el Obispo y la celda
de Amparo; estas runs no guardan ni dan Perlas) y Configurar mando. Desde la pausa se vuelve con
"Menú principal" y la run real queda guardada.

**Amparo, la guardesa**: la tienda del Santuario está cerrada hasta rescatarla. Está en una celda
custodiada (con un élite) en uno de los 3 pisos del Muelle, en un callejón sin salida lejano. Hay
que despejar la sala y abrir la jaula. Desde entonces vive en el Santuario y se pueden gastar las Perlas.

La run ya no termina en la Sirena: tras vencerla, una escalera baja a la **Catedral Sumergida**
(pisos 4-6) y la versión termina con el **Obispo Coral**.

- **Vitrales**: se agrietan al pisarlos y 1,2 s después se rompen en un pozo. El Destello no los
  agrieta. Si un enemigo que camina está encima cuando se rompen, cae: pierde un 30 % de vida.
- **Candelabros**: un golpe, el Haz, el Arpón, la Campana o el Fogonazo los encienden. Dan una luz
  fija de 64 px y +2 de Luz la primera vez. La onda del Campanero los apaga.
- **Enemigos**: Monaguillo de sal (abanico de cristales a distancia), Campanero (la onda baja tu
  luz un 40 % durante 3 s), Vela errante (kamikaze que roba 5 de Luz) y Cofrade de coral (golpe en
  línea de 64 px; al morir deja coral 4 s). Afijo nuevo: **Bendecido** (escudo que absorbe un golpe cada 4 s).
- **Capilla de la ofrenda**: un exvoto raro o épico a cambio de 10 de Luz máxima o de la mitad de tu Luz.
- **El Obispo Coral** (1.500 de vida, 3 fases): barridos del báculo e invocaciones; columnas de luz
  que curan a sus fieles; el coral encoge la arena. Da 12 Perlas y desbloquea la Campana.
- **Historia**: título de capítulo al entrar en cada bioma y el recuerdo de cada jefe la primera vez
  que cae (Ondina, la cantora del muelle; Teodoro, el obispo).
- Debug: F11 salta a la Catedral (o vuelve al Muelle).

## F2-base · Santuario y armas

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
- **Velar**: mantén LB (C en PC) para tapar el farol. La luz baja a 56 px, te mueves al 70 %
  y no atacas; tras 0,4 s los enemigos a más de 64 px te pierden el rastro y te buscan. Tres
  exvotos lo convierten en ataque: Pabilo corto (Fogonazo de 10 de daño al soltar), Sal en los
  ojos (el Fogonazo aturde 1,2 s) y Marea quieta (velas a velocidad normal y el Fogonazo llega más lejos).
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
| Velar (mantener) | C / Ctrl izq.  | Botón VELAR                    | LB                          |
| Pausa    | Esc / P                | Botón II o atrás               | Start / Select              |
| Debug    | F1 cajas y FPS · F2 luz on/off | —                      | —                           |
| Con F1   | F3 carta y Luz llena · F4 ir al jefe · F5 exvoto (en el Santuario: +50 Perlas y todas las armas) · F6 150 de daño · F8 Isla recibe 40 · F9 salta a la siguiente sala (grandes, antesala y capilla primero) · F11 Catedral / Muelle · F10 exvotos de Velar · F7 mando virtual (teclas 1–0) | — | — |

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

## Siguiente fase: F5 · Arte y balance

Arte final en pixel art (atlas por bioma a partir de los sprites ASCII del documento), balance de
daño y de las Mareas con partidas reales, y pulido de sonido.
