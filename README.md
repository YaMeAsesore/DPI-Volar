# DPI Volar

Aplicación Android que usa el framework VPN del sistema (`VpnService`) para 
interceptar y manipular tráfico a nivel de paquetes, con el objetivo de 
evadir la Inspección Profunda de Paquetes (DPI) que usan ISPs o redes 
restrictivas para bloquear o limitar ciertos servicios.

## Privacidad: qué hace y qué NO hace

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

Es una herramienta de manipulación de paquetes local para evadir 
DPI, no una VPN de anonimato. Si buscas ocultar tu IP o tu ubicación, esta herramienta esta
en contante mejora para implementarlo lo mas pronto posible.

## Características

- Servicio VPN basado en `VpnService` con interfaz TUN
- Interfaz en Jetpack Compose con paleta de colores personalizada
- Control simple de activar/desactivar protección desde la pantalla principal
- Procesamiento 100% local del tráfico

 ![image alt](https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213310.png)  ![image alt](https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213409.png)



![image alt](https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213529.png)  ![image alt](https://github.com/YaMeAsesore/DPI-Volar/blob/02122570b02902f5d06257c3566a6b089ef110d7/Screenshot_20260716_213517.png)
