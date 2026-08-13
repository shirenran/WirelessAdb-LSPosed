# WirelessAdb-LSPosed

**Idioma:** [简体中文](README.md) · [Português (Brasil)](README.pt-BR.md) · [English](README.en.md) · [Español](README.es.md)

**Módulo de LSPosed**: activa automáticamente ADB inalámbrico después del primer desbloqueo tras el arranque, con la dirección y los registros disponibles en la pantalla de estado.

Nombre del paquete: `dev.wirelessadb.autostart`
Versión actual: `1.0.18`

<p align="center">
  <img src="docs/screenshot-es.png" alt="Pantalla de estado del inicio automático de ADB inalámbrico en español" width="360" />
</p>

## Funciones

- **Modo TLS**: Depuración inalámbrica del sistema (`adb_wifi_enabled`), puerto aleatorio y emparejamiento obligatorio
- **Modo TCP**: equivalente a `adb tcpip <port>`, con `5555` como valor predeterminado; el ordenador puede conectarse directamente con `adb connect IP:5555`
- Supervisa la desactivación de la Depuración inalámbrica (solo TLS), la recuperación de la conexión Wi-Fi y el desbloqueo de la pantalla, y la reactiva cuando es necesario
- Copia automáticamente la dirección al portapapeles cuando cambia (espera a que el método de entrada WeChat esté listo para pegar entre dispositivos)
- Omite las direcciones virtuales de VPN `172.19.*` al obtener la dirección IP
- Escribe los registros del inicio del sistema en `Settings.Global`, que pueden consultarse en la pantalla de estado

## Instalación

1. Instala el APK de Release
2. Activa este módulo en **LSPosed**
3. Selecciona **Marco del sistema** (`android`) como ámbito
4. Reinicia el teléfono y completa el primer desbloqueo

> Úsalo solo en redes locales de confianza. Cambiar a TCP reinicia `adbd`, lo que puede desconectar la sesión ADB actual.

## Compilación

```bash
./gradlew :app:assembleRelease
```

Resultado: `app/build/outputs/apk/release/app-release.apk`

Es necesario configurar localmente el Android SDK (`sdk.dir` en `local.properties` o la variable de entorno `ANDROID_HOME`).

## Licencia

MIT
