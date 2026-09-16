# Changelog

## [2.1.0] - 2026-08-27

Tercera ronda de correcciones, enfocada en dos problemas que persistían
tras la v2.0.0 y que se detectaron con logs de dispositivo real: consumo
de batería/calor todavía por encima de lo esperado con navegación pesada,
y una lentitud notable de conexión con muchas sesiones concurrentes
(errores en cascada tipo `Socket is closed` / `Connection reset` /
`Broken pipe`).

### Corregido

- **Agotamiento del pool de hilos de red (`NetworkDispatcher`).** El
  límite fijo de 48 hilos introducido en la v2.0.0 partía de la premisa
  de que menos hilos = menos calor, válida para hilos que hacen trabajo
  de CPU, pero no para hilos bloqueados en `socket.read()`/`connect()`
  esperando red (que el sistema operativo mantiene dormidos, sin costo
  de CPU). Con navegación normal (30-50 conexiones concurrentes por
  página), el pool de 48 se saturaba: conexiones nuevas se quedaban sin
  hilo disponible, expiraban su timeout, y para cuando conseguían uno el
  navegador ya había cerrado su lado, produciendo la cascada de errores
  de socket mencionada arriba y reintentos constantes (percibidos como
  "internet más lento"). Se reemplazó por un `cachedThreadPool` sin techo
  artificial bajo, dimensionado dinámicamente según la demanda real.
- **Contención de lock al escribir en la interfaz TUN.** Todas las
  sesiones TCP y la resolución DNS competían por un único
  `synchronized(tunOutput)` compartido en cada paquete saliente. Con
  muchas conexiones simultáneas esto generaba cambios de contexto
  constantes entre hilos. Se introdujo `TunWriter`, que serializa todas
  las escrituras a través de un canal con una única corrutina
  consumidora, eliminando la contención sin sacrificar el orden de
  escritura.
- **Corrutina nueva por cada paquete de datos entrante solo para el
  ACK.** `TcpSession.onClientData` lanzaba `scope.launch { sendControl(TCP_ACK) }`
  por cada paquete recibido del cliente — cientos o miles por segundo
  bajo tráfico normal. Como el ACK de TCP es acumulativo, se reemplazó
  por un canal `CONFLATED` con un único consumidor por sesión: ráfagas
  de paquetes se coalescen en un solo ACK con el `seq` más reciente, sin
  perder la confirmación casi inmediata que evita retransmisiones.
- **Cálculo innecesario de campos en `IPv4Packet`.** Los campos
  (`sourceIp`, `destIp`, `sourceIpBytes`, `destIpBytes`, etc.) eran `val`
  eager, calculados para *todo* paquete que cruza la VPN, incluyendo
  paquetes descartados dos líneas después por no ser TCP/UDP. Se
  convirtieron en `by lazy`, calculándose solo si de verdad se llegan a
  usar.

### Notas de verificación

- Confirmado en logs de dispositivo: tras el cambio de `NetworkDispatcher`
  desaparecieron los errores de socket en cascada y se observó mejora
  notable de velocidad de navegación.
- Prueba de temperatura (5:40 min, mismo navegador, condiciones no
  perfectamente controladas) mostró una diferencia de ~1°C
  promedio/pico frente a sin la app activa — dentro de lo esperable
  para una VPN en funcionamiento normal, y muy por debajo del
  calentamiento severo reportado antes de esta ronda de cambios.


## [2.0.0] - 2026-07-27

Segunda versión funcional de DPI-Volar. Se enfoca en tres problemas
detectados en el uso real de la v1: consumo excesivo de batería/calor,
lentitud al abrir sitios nuevos, y estabilidad de las conexiones TCP
retransmitidas. También se añade cache de DNS y fragmentación consciente
del SNI para la técnica de evasión.

### Corregido

- **Busy-loop en el bucle de reenvío de paquetes (`MyVpnService.forwardPackets`)**
  que podía dejar un hilo girando al 100% de CPU sin bloquear cuando
  `read()` devolvía `0` o `-1`. Causa principal del calentamiento del
  dispositivo. Ahora se distingue `-1` (fin de stream, se corta el bucle)
  de `0` (se cede el hilo con un pequeño `delay`), y se añadió un backoff
  en el `catch` para que un error recurrente no se convierta en otro
  bucle caliente.
- **Paralelismo excesivo en el pool de I/O de red.** Se introdujo
  `NetworkDispatcher`, un `CoroutineDispatcher` propio y separado del
  `Dispatchers.IO` compartido por el resto de la app, para que docenas de
  sesiones TCP simultáneas no agoten el pool general (64 hilos) y
  retrasen conexiones nuevas. El límite se fijó en un valor moderado (no
  arbitrariamente alto) para no generar cambio de contexto excesivo en
  un móvil típico de 4-8 núcleos.
- **Candado inconsistente al escribir en la interfaz TUN.** `TcpSession`
  y `MyVpnService` usaban candados distintos (`@Synchronized` sobre la
  instancia vs. `synchronized(tunOutput)`) para escribir en el mismo
  descriptor compartido, lo que permitía escrituras intercaladas entre
  sesiones y corrupción de paquetes. Ahora todas las escrituras a la TUN
  comparten el mismo candado.
- **Doble RTT por cada conexión TCP nueva.** El handshake con el cliente
  (SYN-ACK) esperaba a que terminara la conexión real contra el servidor
  destino antes de contestar. Ahora se contesta el SYN-ACK de inmediato y
  la conexión real se establece en paralelo, ahorrando un RTT completo
  por conexión — perceptible sobre todo en páginas que abren muchas
  conexiones en paralelo.
- **Tormenta de retransmisiones TCP por ACK tardío.** Al retrasar el ACK
  de los datos del cliente hasta que la conexión real terminaba, el
  temporizador de retransmisión del cliente expiraba antes de tiempo y
  reenviaba el mismo `ClientHello`, que se duplicaba hacia el servidor
  real y este cerraba la conexión (visible en logs como ráfagas masivas
  de `Socket closed` y `ClientHello detectado` repetido para el mismo
  puerto). Se corrigió ACKeando los datos en cuanto entran al buffer
  (`onClientData`), de forma independiente a cuándo se reenvían.
- **Bloqueo del bucle principal de lectura de paquetes.** El ACK
  inmediato del punto anterior se despachaba de forma síncrona dentro de
  `onClientData`, que es llamado directamente por el único hilo que lee
  todos los paquetes de la TUN — bajo carga (muchas sesiones en
  paralelo) esto serializaba todo el tráfico de la app a través de un
  único candado de escritura. Se movió el envío del ACK a una corrutina
  aparte para no bloquear la lectura de paquetes entrantes.

### Añadido

- **`DnsCache`**: cache en memoria de respuestas DNS (TTL fijo de 30s)
  para evitar resoluciones repetidas al cargar páginas con muchos
  recursos del mismo dominio/CDN.
- **Fragmentación consciente del SNI (`TlsParser.findSniHostname`)**: en
  vez de cortar el `ClientHello` en una posición fija arbitraria, la
  técnica `SPLIT`/`DISORDER` ahora localiza el campo SNI dentro de las
  extensiones TLS y corta justo dentro del nombre de dominio cuando es
  posible, con fallback a posición fija si no se encuentra.
- `socket.tcpNoDelay = true` al conectar, necesario para que el
  algoritmo de Nagle no reúna los fragmentos separados en un solo
  paquete TCP y anule el propósito de la técnica de fragmentación.

### Cambiado

- Timeout de conexión al servidor real reducido de 5000ms a 3000ms, para
  que un endpoint caído falle más rápido y el navegador pueda reintentar
  con otro sin esperar tanto.

### Notas conocidas / pendiente

- La técnica `FAKE_PACKET` sigue excluida de la rotación automática:
  sin control de TTL a nivel de socket (requeriría sockets crudos/NDK),
  el paquete señuelo llega al servidor real y corrompe el stream TLS.
- El cache DNS usa un TTL fijo, no el TTL real del registro — aceptable
  para navegación normal, pero puede servir una IP vieja hasta por 30s
  si un dominio cambia de IP muy rápido (balanceadores agresivos).
- Las mejoras de esta versión son de estabilidad/rendimiento,
  no cambian la efectividad de evasión en sí.

## [1.0.0] - 2026-07-11

Primera versión funcional: `VpnService` con interfaz TUN, NAT manual para
TCP/UDP, detección de `ClientHello` TLS, y técnicas básicas de
fragmentación (`SPLIT`, `DISORDER`, `FAKE_PACKET` no habilitada) con
rotación automática por fallos consecutivos (`TechniqueStats`).
