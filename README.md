# DPI Volar

**Evasión local de Deep Packet Inspection (DPI) en Android**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#cómo-compilar)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](#)

DPI Volar es una aplicación Android que usa el framework VPN del sistema
(`VpnService`) para interceptar y manipular tráfico a nivel de paquetes,
con el objetivo de evadir la Inspección Profunda de Paquetes (DPI) que
usan algunos ISPs o redes restrictivas para bloquear o limitar ciertos
servicios. No es un proxy ni un túnel hacia un servidor externo — todo el
procesamiento pasa por tu propio dispositivo.

[Descargar](#descargas) · [Qué hace y qué no hace](#qué-hace-y-qué-no-hace) · [Cómo compilar](#cómo-compilar)

---

## Descargas

| Versión | APK | Cambios |
|---|---|---|
| **v2.2.0** (actual) | [Descargar APK](https://www.mediafire.com/file/kjncklrp2l5je56/DPI_volar.apk/file) | [Ver detalle](CHANGELOG.md#220---2026-09-16) |
| v2.1.0 | [Descargar APK](https://www.mediafire.com/file/2klrkbe8ulmkhar/app-debug.apk/file) | [Ver detalle](CHANGELOG.md#210---2026-08-27) |
| v2.0.0 | [Descargar APK](https://www.mediafire.com/file/43jraftynsfnd4a/DPI-VOLARv2.apk/file) | [Ver detalle](CHANGELOG.md#200---2026-07-27) |
| v1.0.0 | [Descargar APK](https://www.mediafire.com/file/w9bmv0ilbtb9a43/DPI-VOLAR.apk/file) | — |

El historial completo, con el porqué de cada cambio, está en [CHANGELOG.md](CHANGELOG.md).

La v2.2.0 corrige un fallo de estabilidad que podía hacer que la app
dejara de funcionar tras un tiempo de uso: las sesiones TCP podían
quedarse bloqueadas indefinidamente, agotando hilos y descriptores de
archivo. También incorpora el icono adaptativo completo, con foreground
redimensionado para respetar la zona segura del launcher, fondo beige y
recursos legacy regenerados para las cinco densidades.

---

## Qué hace y qué no hace

Vale la pena ser claro con esto, porque "VPN" suele asociarse con
anonimizar tráfico o cambiar de ubicación, y DPI Volar no hace eso.

Tu tráfico no sale a un servidor externo. A diferencia de una VPN
comercial, DPI Volar no enruta tu conexión a través de un servidor
remoto: todo el procesamiento ocurre en tu propio teléfono, usando la
interfaz TUN que expone Android. Tampoco oculta tu IP ni cambia tu
ubicación aparente — tu ISP sigue viendo que te conectas desde tu IP
real. Lo que cambia es cómo se ve tu tráfico a nivel de paquete, para
que a un sistema de DPI le cueste más identificarlo y bloquearlo.

No hay recolección ni envío de datos a terceros. No existe un backend,
servidor propio, ni telemetría — todo el código que procesa tus
paquetes está en este repositorio, se puede leer línea por línea.

Android la trata como "VPN" a nivel de sistema porque técnicamente usa
`VpnService` (por eso aparece el ícono de llave en la barra de estado),
pero eso es solo el mecanismo que da acceso a interceptar paquetes, no
implica un túnel hacia ningún servidor externo.

Es una herramienta de manipulación de paquetes local para
evadir DPI, no una VPN de anonimato. Ocultar IP o ubicación no está
implementado todavía.

---

## Características

- Servicio VPN basado en `VpnService` con interfaz TUN
- Detección y fragmentación del handshake TLS (`ClientHello`), con corte
  consciente del campo SNI
- Rotación automática de técnica de evasión (SPLIT / DISORDER) ante
  fallos consecutivos
- Cache de resoluciones DNS para acelerar la carga de páginas
- Interfaz en Jetpack Compose con paleta de colores personalizada
- Control simple de activar/desactivar protección desde la pantalla principal
- Procesamiento 100% local del tráfico

<p align="center">
  <img src="https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213310.png?raw=true" width="180"/>
  <img src="https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213409.png?raw=true" width="180"/>
  <img src="https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213529.png?raw=true" width="180"/>
  <img src="https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213517.png?raw=true" width="180"/>
</p>

---

## Cómo compilar

Requisitos: Android Studio reciente (Hedgehog o superior), JDK 17. Kotlin
lo maneja Gradle solo, no hace falta instalarlo aparte. Las versiones
exactas de SDK mínimo/objetivo están en `build.gradle.kts`.

```bash
git clone https://github.com/YaMeAsesore/DPI-Volar.git
```

Ábrelo en Android Studio (`Open` → selecciona la carpeta del proyecto),
espera a que Gradle sincronice, conecta un dispositivo o emulador con
Android 8.0 (API 26) o superior, y ejecútalo con el botón Run.

---

## Estado del proyecto

La v2 trae la lógica funcional de evasión de DPI: detección y
fragmentación del ClientHello, corte consciente del SNI, rotación
automática de técnica y cache de DNS. La v2.1.0 optimizó el pool de
hilos, las escrituras hacia la TUN, los ACK y el cálculo de campos de
paquetes. La v2.2.0 añade un timeout de lectura y cierre limpio para
sesiones TCP inactivas, además de reemplazar sesiones antiguas cuando
llega un SYN nuevo con la misma clave. También actualiza el icono
adaptativo y elimina recursos de imagen que no son válidos como recursos
Android por sus nombres con mayúsculas. El detalle completo está en el
[CHANGELOG](CHANGELOG.md).

Sigue pendiente validar el consumo de batería y la temperatura en
dispositivo físico bajo uso prolongado (lo probado hasta ahora fue
principalmente en emulador), y revisar por qué el parser de SNI a veces
no encuentra el hostname dentro del ClientHello y cae a un corte de
posición fija — no afecta la velocidad, pero sí reduce qué tan bien
funciona la fragmentación contra DPI real.

Las siguientes fases se enfocan en eso: robustecer las técnicas de
evasión y probarlas contra sistemas de DPI reales, no solo contra
capturas de tráfico normal.

---

## Contribuir

El proyecto es de código abierto. Puedes hacer fork y modificarlo
libremente para tu propio uso.

## Licencia

MIT — el detalle está en [LICENSE](LICENSE).
