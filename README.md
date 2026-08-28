<div align="center">

#  DPI Volar

**Evasión local de Deep Packet Inspection (DPI) en Android**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#cómo-compilar)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](#)
[![Latest Release](https://img.shields.io/github/v/release/YaMeAsesore/DPI-Volar)](https://github.com/YaMeAsesore/DPI-Volar/releases/latest)

Aplicación Android que usa el framework VPN del sistema (`VpnService`) para
interceptar y manipular tráfico a nivel de paquetes, con el objetivo de
evadir la Inspección Profunda de Paquetes (DPI) que usan ISPs o redes
restrictivas para bloquear o limitar ciertos servicios.

[Descargar](#-descargas) · [Características](#-características) · [Compilar](#-cómo-compilar) · [Privacidad](#-privacidad-qué-hace-y-qué-no-hace)

</div>

---

##  Descargas

| Versión | APK | Notas de la versión |
|---|---|---|
| **v2.0.0**  actual | [Descargar APK](https://www.mediafire.com/file/43jraftynsfnd4a/DPI-VOLARv2.apk/file) | [Ver cambios](https://github.com/YaMeAsesore/DPI-Volar/releases/tag/v2.0.0) |
| v1.0.0 | [Descargar APK](https://www.mediafire.com/file/w9bmv0ilbtb9a43/DPI-VOLAR.apk/file) | — |

> El historial completo de cambios está en [CHANGELOG.md](CHANGELOG.md).

---

##  Privacidad: qué hace y qué NO hace

Es importante ser claro sobre esto, porque "VPN" suele asociarse con anonimizar
tráfico o cambiar tu ubicación, y **DPI Volar no hace eso**:

- **Tu tráfico NO sale a un servidor externo.** A diferencia de una VPN
  comercial (NordVPN, ExpressVPN, etc.), DPI Volar no enruta tu conexión a
  través de un servidor remoto. El procesamiento ocurre localmente, en tu
  propio dispositivo, usando la interfaz TUN que provee Android.
- **No oculta tu IP ni cambia tu ubicación aparente.** Tu ISP sigue viendo
  que te conectas desde tu IP real; lo que cambia es *cómo* se ve tu tráfico
  a nivel de paquete, para dificultar que sistemas de DPI lo identifiquen
  y bloqueen.
- **No hay recolección ni envío de datos a terceros.** No existe un backend,
  servidor propio, ni telemetría en este proyecto. Todo el código que procesa
  tus paquetes es visible aquí mismo, en este repositorio.
- **Android la trata como "VPN" a nivel de sistema** porque técnicamente usa
  `VpnService` (por eso verás el ícono de llave en la barra de estado), pero
  esto es solo el mecanismo que usa Android para dar acceso a interceptar
  paquetes — no implica que haya un túnel hacia un servidor externo.

Es una herramienta de manipulación de paquetes local para evadir DPI, **no**
una VPN de anonimato. Si buscas ocultar tu IP o tu ubicación, esta
funcionalidad está en constante desarrollo para implementarse lo más pronto
posible.

---

##  Características

- Servicio VPN basado en `VpnService` con interfaz TUN
- Detección y fragmentación del handshake TLS (`ClientHello`), con corte
  consciente del campo SNI
- Rotación automática de técnica de evasión (`SPLIT` / `DISORDER`) ante
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

##  Cómo compilar

### Requisitos

- Android Studio (versión reciente, Hedgehog o superior recomendado)
- JDK 17
- Kotlin (gestionado por Gradle, no requiere instalación aparte)
- SDK de Android mínimo/objetivo: revisa `build.gradle.kts` para las versiones exactas

### Pasos

1. Clona el repositorio:
```bash
   git clone https://github.com/YaMeAsesore/DPI-Volar.git
```
2. Ábrelo en Android Studio (`Open` → selecciona la carpeta del proyecto)
3. Espera a que Gradle sincronice las dependencias
4. Conecta un dispositivo o usa un emulador con Android 8.0 (API 26) o superior
5. Ejecuta con el botón ▶️ **Run**

---

##  Estado del proyecto

Este proyecto está en desarrollo activo. La v2 ya incluye la lógica
funcional de evasión de DPI: detección y fragmentación del handshake TLS,
corte consciente del SNI, rotación automática de técnica y cache de DNS.

La rama `main` incluye además una segunda ronda de optimizaciones de
rendimiento (ver [CHANGELOG.md](CHANGELOG.md), v2.1.0) enfocada en
consumo de batería/calor y estabilidad de conexión bajo navegación con
muchas conexiones concurrentes: un pool de hilos de red sin cuello de
botella artificial, un único escritor serializado hacia la interfaz TUN,
confirmaciones TCP (ACK) coalescidas en vez de una corrutina por paquete,
y cálculo perezoso de campos en el parseo de paquetes IPv4. Estos cambios
todavía no están empaquetados en un release/APK etiquetado — el
[APK v2.0.0](#-descargas) disponible para descarga no los incluye
todavía.

Las siguientes fases se enfocan en robustecer las técnicas de evasión y
validarlas contra sistemas de DPI reales.

---

##  Contribuir

Este proyecto es de código abierto. Puedes hacer fork y modificarlo
libremente para tu propio uso.

## 📄 Licencia

Este proyecto está bajo la licencia MIT — consulta el archivo
[LICENSE](LICENSE) para más detalles.
